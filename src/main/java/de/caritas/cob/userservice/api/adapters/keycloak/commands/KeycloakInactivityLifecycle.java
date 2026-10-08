package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.port.out.IdentityInactivityLifecycle;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Its caller can name a target, but only persisted workflow state grants an effect. */
@Component
@RequiredArgsConstructor
public class KeycloakInactivityLifecycle implements IdentityInactivityLifecycle {
  private record Workflow(String identityId, Long tenant, AccountInactivityService.Status status) {}

  private final JdbcTemplate jdbc;
  private final KeycloakTaskCommands commands;

  private Workflow workflow(String id) {
    var rows =
        jdbc.query(
            "SELECT identity_id,tenant_id,status FROM account_inactivity WHERE identity_id=?",
            (row, n) ->
                new Workflow(
                    row.getString("identity_id"),
                    (Long) row.getObject("tenant_id"),
                    AccountInactivityService.Status.valueOf(row.getString("status"))),
            id);
    if (rows.size() != 1)
      throw new AccessDeniedException("Account lifecycle has no persisted workflow owner");
    return rows.getFirst();
  }

  @org.springframework.transaction.annotation.Transactional
  @Override
  public IdentityCommandAuthorization authorizeDeletion(String id) {
    var state = workflow(id);
    var permits =
        jdbc.query(
            "SELECT deletion_authorized FROM account_inactivity_access_state WHERE identity_id=? AND restored=FALSE",
            (row, n) -> row.getBoolean("deletion_authorized"),
            id);
    if (permits.size() != 1 || !permits.getFirst())
      throw new AccessDeniedException("Identity deletion lacks durable lifecycle authorization");
    return IdentityCommandAuthorization.inactivityWorkflow(
        state.identityId(), state.tenant(), state.status(), "account.delete", true);
  }

  @Override
  public State status(String id) {
    var state = workflow(id);
    return commands.lifecycleStatus(
        id,
        IdentityCommandAuthorization.inactivityWorkflow(
            state.identityId(), state.tenant(), state.status(), "account.lifecycle-status", null));
  }

  @org.springframework.transaction.annotation.Transactional
  @Override
  public void suspend(String id) {
    var state = workflow(id);
    commands.suspend(
        id,
        IdentityCommandAuthorization.inactivityWorkflow(
            state.identityId(), state.tenant(), state.status(), "account.suspend", null));
  }

  @org.springframework.transaction.annotation.Transactional
  @Override
  public void restoreOriginalAccess(String id) {
    var state = workflow(id);
    var originals =
        jdbc.query(
            "SELECT keycloak_enabled FROM account_inactivity_access_state WHERE identity_id=? AND restored=FALSE",
            (row, n) -> row.getBoolean("keycloak_enabled"),
            id);
    if (originals.size() != 1)
      throw new AccessDeniedException(
          "Access restoration lacks its durable original enabled state");
    boolean enabled = originals.getFirst();
    commands.restoreAccess(
        id,
        enabled,
        IdentityCommandAuthorization.inactivityWorkflow(
            state.identityId(), state.tenant(), state.status(), "account.restore", enabled));
  }
}
