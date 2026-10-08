package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffectException.Code.*;
import static de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffectException.Target.*;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.port.out.IdentityInactivityLifecycle;
import de.caritas.cob.userservice.api.workflow.delete.service.InactiveAskerDeletionService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

/** Coordinates confirmed external effects; its recovery metadata commits before any mutation. */
@Component
public class DefaultAccountInactivityEffects implements AccountInactivityEffects {
  private record Access(boolean enabled, boolean restored, boolean deletionAuthorized) {}

  private record MatrixAccess(String id, boolean locked) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate durable;
  private final IdentityInactivityLifecycle keycloak;
  private final MatrixSynapseService matrix;
  private final AccountInactivityMediaClient media;
  private final ObjectProvider<InactiveAskerDeletionService> deletion;

  public DefaultAccountInactivityEffects(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      IdentityInactivityLifecycle keycloak,
      MatrixSynapseService matrix,
      AccountInactivityMediaClient media,
      ObjectProvider<InactiveAskerDeletionService> deletion) {
    this.jdbc = jdbc;
    this.keycloak = keycloak;
    this.matrix = matrix;
    this.media = media;
    this.deletion = deletion;
    durable = new TransactionTemplate(manager);
    durable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Set<Role> currentRoles(String id) {
    try {
      Set<Role> roles = new HashSet<>(keycloak.status(id).roles());
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM consultant WHERE consultant_id=?", Long.class, id)
          > 0) roles.add(Role.CONSULTANT);
      if (jdbc.queryForObject("SELECT COUNT(*) FROM admin WHERE admin_id=?", Long.class, id) > 0)
        roles.add(Role.OTHER);
      return roles.isEmpty() ? Set.of(Role.UNKNOWN) : Set.copyOf(roles);
    } catch (org.springframework.dao.DataAccessException failure) {
      throw new AccountInactivityEffectException(DATABASE, FAILED);
    } catch (RuntimeException failure) {
      throw new AccountInactivityEffectException(KEYCLOAK, FAILED);
    }
  }

  @Override
  public boolean suspend(String id) {
    capture(id, false);
    try {
      keycloak.suspend(id);
      var state = keycloak.status(id);
      if (state.enabled() || state.sessionCount() != 0)
        throw new AccountInactivityEffectException(KEYCLOAK, UNCONFIRMED);
    } catch (AccountInactivityEffectException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AccountInactivityEffectException(KEYCLOAK, FAILED);
    }
    for (var access : matrixStates(id))
      if (!matrix.setAccountSuspended(access.id(), true))
        throw new AccountInactivityEffectException(MATRIX, UNCONFIRMED);
    media.revoke(matrixStates(id).stream().map(MatrixAccess::id).toList());
    return true;
  }

  @Override
  public boolean reactivate(String id) {
    var original =
        state(id).orElseThrow(() -> new AccountInactivityEffectException(DATABASE, FAILED));
    if (original.restored()) return true;
    // Restore Matrix first. Keycloak remains disabled until all chat access states are confirmed.
    for (var access : matrixStates(id))
      if (!matrix.setAccountSuspended(access.id(), access.locked()))
        throw new AccountInactivityEffectException(MATRIX, UNCONFIRMED);
    media.restore(matrixStates(id).stream().map(MatrixAccess::id).toList());
    try {
      keycloak.restoreOriginalAccess(id);
      if (original.enabled() != keycloak.status(id).enabled())
        throw new AccountInactivityEffectException(KEYCLOAK, UNCONFIRMED);
    } catch (AccountInactivityEffectException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new AccountInactivityEffectException(KEYCLOAK, FAILED);
    }
    durable.executeWithoutResult(
        ignored ->
            jdbc.update(
                "UPDATE account_inactivity_access_state SET restored=TRUE WHERE identity_id=?",
                id));
    return true;
  }

  @Override
  public boolean delete(String id) {
    boolean remoteAlreadyGone = false;
    try {
      var roles = currentRoles(id);
      if (roles.contains(Role.UNKNOWN))
        throw new AccountInactivityEffectException(KEYCLOAK, UNCONFIRMED);
      if (!roles.equals(Set.of(Role.ASKER)))
        throw new AccountInactivityEffectException(KEYCLOAK, ROLE_CHANGED);
      capture(id, true);
    } catch (AccountInactivityEffectException failure) {
      // A missing remote identity is expected only after an already-authorized partial deletion.
      var existing = state(id);
      if (existing.isEmpty() || !existing.get().deletionAuthorized() || !keycloakGone(id))
        throw failure;
      remoteAlreadyGone = true;
    }
    if (!remoteAlreadyGone) {
      suspend(id);
      var finalRoles = currentRoles(id);
      if (finalRoles.contains(Role.UNKNOWN))
        throw new AccountInactivityEffectException(KEYCLOAK, UNCONFIRMED);
      if (!finalRoles.equals(Set.of(Role.ASKER)))
        throw new AccountInactivityEffectException(KEYCLOAK, ROLE_CHANGED);
    }
    var errors = deletion.getObject().delete(id, keycloak.authorizeDeletion(id));
    if (errors == null) throw new AccountInactivityEffectException(DATABASE, UNCONFIRMED);
    if (!errors.isEmpty()) {
      var target = errors.getFirst().getDeletionTargetType();
      var safeTarget =
          target == null || target.name().equals("ALL")
              ? OTHER
              : AccountInactivityEffectException.Target.valueOf(target.name());
      throw new AccountInactivityEffectException(safeTarget, UNCONFIRMED);
    }
    if (!keycloakGone(id)) throw new AccountInactivityEffectException(KEYCLOAK, UNCONFIRMED);
    media.forget(matrixStates(id).stream().map(MatrixAccess::id).toList());
    // These recovery identifiers are needed only until all systems confirm deletion. This
    // transaction also commits the lifecycle DELETED state; rollback preserves retry metadata.
    jdbc.update("DELETE FROM account_inactivity_matrix_state WHERE identity_id=?", id);
    jdbc.update("DELETE FROM account_inactivity_access_state WHERE identity_id=?", id);
    return true;
  }

  private boolean keycloakGone(String id) {
    try {
      keycloak.status(id);
      return false;
    } catch (HttpClientErrorException.NotFound absent) {
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private Optional<Access> state(String id) {
    return jdbc
        .query(
            "SELECT * FROM account_inactivity_access_state WHERE identity_id=?",
            (r, n) ->
                new Access(
                    r.getBoolean("keycloak_enabled"),
                    r.getBoolean("restored"),
                    r.getBoolean("deletion_authorized")),
            id)
        .stream()
        .findFirst();
  }

  private List<MatrixAccess> matrixStates(String id) {
    return jdbc.query(
        "SELECT * FROM account_inactivity_matrix_state WHERE identity_id=?",
        (r, n) -> new MatrixAccess(r.getString("matrix_user_id"), r.getBoolean("original_locked")),
        id);
  }

  private void capture(String id, boolean deleting) {
    durable.executeWithoutResult(
        ignored -> {
          var existing = state(id);
          if (existing.isPresent() && !existing.get().restored()) {
            if (deleting)
              jdbc.update(
                  "UPDATE account_inactivity_access_state SET deletion_authorized=TRUE WHERE"
                      + " identity_id=?",
                  id);
            return;
          }
          boolean enabled;
          try {
            enabled = keycloak.status(id).enabled();
          } catch (AccountInactivityEffectException failure) {
            throw failure;
          } catch (RuntimeException failure) {
            throw new AccountInactivityEffectException(KEYCLOAK, FAILED);
          }
          var matrixIds =
              jdbc.queryForList(
                  "SELECT matrix_user_id FROM user WHERE user_id=? AND matrix_user_id IS NOT NULL"
                      + " UNION SELECT matrix_user_id FROM consultant WHERE consultant_id=? AND"
                      + " matrix_user_id IS NOT NULL",
                  String.class,
                  id,
                  id);
          var snapshots = new java.util.ArrayList<MatrixAccess>();
          for (var matrixId : matrixIds) {
            if (matrixId.isBlank()) continue;
            var locked =
                matrix
                    .getAccountLocked(matrixId)
                    .orElseThrow(() -> new AccountInactivityEffectException(MATRIX, UNCONFIRMED));
            snapshots.add(new MatrixAccess(matrixId, locked));
          }
          jdbc.update("DELETE FROM account_inactivity_matrix_state WHERE identity_id=?", id);
          jdbc.update("DELETE FROM account_inactivity_access_state WHERE identity_id=?", id);
          jdbc.update(
              "INSERT INTO"
                  + " account_inactivity_access_state(identity_id,keycloak_enabled,restored,deletion_authorized)"
                  + " VALUES(?,?,FALSE,?)",
              id,
              enabled,
              deleting);
          for (var access : snapshots)
            jdbc.update(
                "INSERT INTO"
                    + " account_inactivity_matrix_state(identity_id,matrix_user_id,original_locked)"
                    + " VALUES(?,?,?)",
                id,
                access.id(),
                access.locked());
        });
  }
}
