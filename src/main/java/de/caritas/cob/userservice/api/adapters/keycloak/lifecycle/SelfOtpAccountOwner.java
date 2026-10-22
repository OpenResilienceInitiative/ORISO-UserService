package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Issues only the local SELF origin kind; native permission is checked separately on every RPC. */
final class SelfOtpAccountOwner {
  enum Kind {
    ORDINARY,
    TENANT_ADMIN,
    PLATFORM_ADMIN
  }

  private final JdbcTemplate jdbc;

  SelfOtpAccountOwner(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  Kind require(String account, Long tenant) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw forbidden();
    var users =
        jdbc.queryForList(
            "select tenant_id,delete_date from user where user_id=? for update", account);
    var admins =
        jdbc.queryForList("select tenant_id,type from admin where admin_id=? for update", account);
    if (users.size() == 1 && admins.isEmpty()) {
      var user = users.getFirst();
      if (user.get("delete_date") != null || !Objects.equals(tenant, number(user.get("tenant_id"))))
        throw forbidden();
      return Kind.ORDINARY;
    }
    if (!users.isEmpty() || admins.size() != 1 || tenant == null) throw forbidden();
    var admin = admins.getFirst();
    if (!Objects.equals(tenant, number(admin.get("tenant_id")))) throw forbidden();
    Kind kind;
    if (tenant > 0 && "TENANT".equals(admin.get("type"))) kind = Kind.TENANT_ADMIN;
    else if (tenant == 0 && "SUPER".equals(admin.get("type"))) kind = Kind.PLATFORM_ADMIN;
    else throw forbidden();
    // These are causal application commit facts, not a replacement for native provenance,
    // current issuer binding, explicit operation reach or the actual recipient session.
    var origins =
        jdbc.queryForList(
            "select a.invite_id from protected_account_activation a"
                + " join protected_invitation_acceptance p on p.invite_id=a.invite_id"
                + " and p.claim_id=a.claim_id and p.attempt_id=a.attempt_id"
                + " and p.execution_id=a.execution_id and p.account_id=a.account_id"
                + " join account_invite i on i.id=a.invite_id"
                + " and i.accepted_by_user_id=a.account_id"
                + " where a.account_id=? and a.state='COMMITTED'"
                + " and a.native_commit_receipt is not null and a.native_commit_receipt<>''"
                + " and i.purpose='INVITE' and i.provisioning_status='COMPLETED'"
                + " and i.target_role=? and i.tenant_id=? for update",
            account,
            kind.name(),
            tenant);
    if (origins.size() != 1) throw forbidden();
    return kind;
  }

  private static Long number(Object value) {
    if (value == null) return null;
    if (!(value instanceof Number number)) throw forbidden();
    return number.longValue();
  }

  private static ForbiddenException forbidden() {
    return new ForbiddenException("Current self account is not permitted");
  }
}
