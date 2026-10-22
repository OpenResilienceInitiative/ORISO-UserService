package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffectException.Code.*;
import static de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffectException.Target.*;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.workflow.delete.service.InactiveAskerDeletionService;
import jakarta.ws.rs.NotFoundException;
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

/** Coordinates confirmed external effects; its recovery metadata commits before any mutation. */
@Component
public class DefaultAccountInactivityEffects implements AccountInactivityEffects {
  private record Access(boolean enabled, boolean restored, boolean deletionAuthorized) {}

  private record MatrixAccess(String id, boolean locked) {}

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;

  @org.springframework.beans.factory.annotation.Autowired
  public void setIdentityMode(de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode) { this.identityMode = java.util.Objects.requireNonNull(mode); }

  private void requireLegacyIdentity() {
    if (identityMode != null && identityMode.isFiveActorMode())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate durable;
  private final KeycloakClient keycloak;
  private final MatrixSynapseService matrix;
  private final AccountInactivityMediaClient media;
  private final ObjectProvider<InactiveAskerDeletionService> deletion;

  public DefaultAccountInactivityEffects(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      KeycloakClient keycloak,
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
  public Set<Role> currentRoles(AccountInactivityService.ClaimedWork work) {
    work.requireCurrent();
    var projection = work.nativeRead();
    var roles = new HashSet<Role>();
    projection.effectiveRoles().forEach(name -> classify(roles,name));
    var source = new com.fasterxml.jackson.databind.ObjectMapper();
    try {
      var rows = source.readTree(work.originalSourceJson());
      if (!rows.path("consultant").isEmpty()) roles.add(Role.CONSULTANT);
      if (!rows.path("admin").isEmpty()) roles.add(Role.OTHER);
    } catch (java.io.IOException invalid) { throw new AccountInactivityEffectException(DATABASE,FAILED); }
    work.requireCurrent();
    return roles.isEmpty() ? Set.of(Role.UNKNOWN) : Set.copyOf(roles);
  }

  @Override
  public boolean suspend(AccountInactivityService.ClaimedWork work) {
    work.requireCurrent();
    scopedCapture(work);
    if (!work.suspendNative()) return false;
    for (var access : matrixStates(work.identityId())) {
      work.requireCurrent();
      if (!matrix.setAccountSuspended(access.id(),true)) throw new AccountInactivityEffectException(MATRIX,UNCONFIRMED);
    }
    work.requireCurrent();
    media.revoke(matrixStates(work.identityId()).stream().map(MatrixAccess::id).toList());
    work.requireCurrent(); return true;
  }

  @Override
  public boolean reactivate(AccountInactivityService.ClaimedWork work) {
    work.requireCurrent();
    var original = state(work.identityId()).orElseThrow(() -> new AccountInactivityEffectException(DATABASE,FAILED));
    if (!java.util.Objects.equals(work.suspensionOperationId(),accessOwner(work.identityId()))) throw new AccountInactivityEffectException(DATABASE,FAILED);
    for (var access : matrixStates(work.identityId())) {
      work.requireCurrent();
      if (!matrix.setAccountSuspended(access.id(),access.locked())) throw new AccountInactivityEffectException(MATRIX,UNCONFIRMED);
    }
    work.requireCurrent();
    media.restore(matrixStates(work.identityId()).stream().map(MatrixAccess::id).toList());
    work.requireCurrent();
    if (!work.restoreNative(original.enabled())) return false;
    durable.executeWithoutResult(ignored -> {
      work.requireOwnedSql();
      var latest = state(work.identityId()).orElseThrow(() -> new AccountInactivityEffectException(DATABASE,FAILED));
      if (latest.enabled() != original.enabled() || !java.util.Objects.equals(work.suspensionOperationId(),accessOwner(work.identityId()))) throw new AccountInactivityEffectException(DATABASE,FAILED);
      if (!latest.restored() && jdbc.update("update account_inactivity_access_state set restored=TRUE where identity_id=? and restored=FALSE",work.identityId()) != 1) throw new AccountInactivityEffectException(DATABASE,FAILED);
    }); return true;
  }

  private void scopedCapture(AccountInactivityService.ClaimedWork work) {
    var existing = state(work.identityId());
    if (existing.isPresent() && !existing.get().restored() && work.workId().equals(accessOwner(work.identityId()))) return;
    String phase = jdbc.queryForObject("select native_phase from account_inactivity where identity_id=? and work_id=?",String.class,work.identityId(),work.workId());
    if (!"CLAIMED".equals(phase)) throw new AccountInactivityEffectException(DATABASE,FAILED);
    var projection = work.nativeRead();
    var ids = jdbc.queryForList("SELECT matrix_user_id FROM user WHERE user_id=? AND matrix_user_id IS NOT NULL UNION SELECT matrix_user_id FROM consultant WHERE consultant_id=? AND matrix_user_id IS NOT NULL",String.class,work.identityId(),work.identityId());
    var access = new java.util.ArrayList<MatrixAccess>();
    for (String id : ids) {
      if (id == null || id.isBlank()) throw new AccountInactivityEffectException(MATRIX,UNCONFIRMED);
      work.requireCurrent();
      access.add(new MatrixAccess(id,matrix.getAccountLocked(id).orElseThrow(() -> new AccountInactivityEffectException(MATRIX,UNCONFIRMED))));
    }
    work.requireCurrent();
    durable.executeWithoutResult(ignored -> {
      work.requireOwnedSql();
      var saved = state(work.identityId());
      if (saved.isPresent() && !saved.get().restored() && work.workId().equals(accessOwner(work.identityId()))) return;
      String currentPhase = jdbc.queryForObject("select native_phase from account_inactivity where identity_id=? and work_id=?",String.class,work.identityId(),work.workId());
      if (!"CLAIMED".equals(currentPhase)) throw new AccountInactivityEffectException(DATABASE,FAILED);
      jdbc.update("delete from account_inactivity_matrix_state where identity_id=?",work.identityId());
      jdbc.update("delete from account_inactivity_access_state where identity_id=?",work.identityId());
      jdbc.update("insert into account_inactivity_access_state(identity_id,keycloak_enabled,restored,deletion_authorized) values(?,?,FALSE,FALSE)",work.identityId(),projection.enabled());
      for (var item : access) jdbc.update("insert into account_inactivity_matrix_state(identity_id,matrix_user_id,original_locked) values(?,?,?)",work.identityId(),item.id(),item.locked());
      if (jdbc.update("update account_inactivity set access_work_id=? where identity_id=? and work_id=?",work.workId(),work.identityId(),work.workId()) != 1) throw new AccountInactivityEffectException(DATABASE,FAILED);
    });
  }

  private String accessOwner(String id) {
    return jdbc.queryForObject("select access_work_id from account_inactivity where identity_id=?",String.class,id);
  }

  @Override
  public Set<Role> currentRoles(String id) {
    requireLegacyIdentity();
    try {
      var user = keycloak.getUsersResource().get(id);
      if (user.toRepresentation().getServiceAccountClientId() != null) return Set.of(Role.UNKNOWN);
      Set<Role> roles = new HashSet<>();
      for (var role : user.roles().realmLevel().listEffective()) {
        var name = role.getName();
        if (name != null
            && (name.equals("offline_access")
                || name.equals("uma_authorization")
                || name.startsWith("default-roles-"))) continue;
        classify(roles, name);
      }
      // Enumerate effective client roles as well, including roles inherited through groups.
      for (var client : keycloak.getRealmResource().clients().findAll()) {
        for (var role : user.roles().clientLevel(client.getId()).listEffective()) {
          var name = role.getName();
          if ("account".equals(client.getClientId())
              && Set.of(
                      "manage-account",
                      "manage-account-links",
                      "view-profile",
                      "view-consent",
                      "manage-consent",
                      "view-applications",
                      "delete-account")
                  .contains(name)) continue;
          classify(roles, name);
        }
      }
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

  private void classify(Set<Role> roles, String name) {
    if (name == null) roles.add(Role.UNKNOWN);
    else if (name.equals("user") || name.equals("anonymous")) roles.add(Role.ASKER);
    else if (name.equals("consultant")) roles.add(Role.CONSULTANT);
    else roles.add(Role.OTHER);
  }

  @Override
  public boolean suspend(String id) {
    requireLegacyIdentity();
    capture(id, false);
    try {
      var user = keycloak.getUsersResource().get(id);
      var representation = user.toRepresentation();
      if (!Boolean.FALSE.equals(representation.isEnabled())) {
        representation.setEnabled(false);
        user.update(representation);
      }
      user.logout();
      if (!Boolean.FALSE.equals(user.toRepresentation().isEnabled())
          || !user.getUserSessions().isEmpty())
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
    requireLegacyIdentity();
    var original =
        state(id).orElseThrow(() -> new AccountInactivityEffectException(DATABASE, FAILED));
    if (original.restored()) return true;
    // Restore Matrix first. Keycloak remains disabled until all chat access states are confirmed.
    for (var access : matrixStates(id))
      if (!matrix.setAccountSuspended(access.id(), access.locked()))
        throw new AccountInactivityEffectException(MATRIX, UNCONFIRMED);
    media.restore(matrixStates(id).stream().map(MatrixAccess::id).toList());
    try {
      var user = keycloak.getUsersResource().get(id);
      var representation = user.toRepresentation();
      representation.setEnabled(original.enabled());
      user.update(representation);
      if (!Boolean.valueOf(original.enabled()).equals(user.toRepresentation().isEnabled()))
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
    requireLegacyIdentity();
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
    var errors = deletion.getObject().delete(id);
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
      keycloak.getUsersResource().get(id).toRepresentation();
      return false;
    } catch (NotFoundException absent) {
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
            var representation = keycloak.getUsersResource().get(id).toRepresentation();
            if (representation.isEnabled() == null)
              throw new AccountInactivityEffectException(KEYCLOAK, UNCONFIRMED);
            enabled = representation.isEnabled();
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
