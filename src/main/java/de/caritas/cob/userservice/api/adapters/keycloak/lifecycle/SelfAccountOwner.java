package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Issues only the local SELF origin kind; native permission is checked separately on every RPC. */
final class SelfAccountOwner {
  enum Kind {
    USER,
    CONSULTANT,
    AGENCY_ADMIN,
    TENANT_ADMIN,
    PLATFORM_ADMIN;

    boolean protectedTarget() {
      return this == TENANT_ADMIN || this == PLATFORM_ADMIN;
    }

    boolean admin() {
      return this != USER && this != CONSULTANT;
    }
  }

  record Owner(Kind kind, boolean consultant, String email, String consultantEmail) {}

  private final JdbcTemplate jdbc;

  SelfAccountOwner(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  Owner require(String account, Long tenant) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw forbidden();
    var users =
        jdbc.queryForList(
            "select tenant_id,delete_date,email from user where user_id=? for update", account);
    var consultants =
        jdbc.queryForList(
            "select tenant_id,delete_date,email from consultant where consultant_id=? for update",
            account);
    var admins =
        jdbc.queryForList(
            "select tenant_id,type,email from admin where admin_id=? for update", account);
    if (users.size() > 1 || consultants.size() > 1 || admins.size() > 1) throw forbidden();
    if (!users.isEmpty() && (!consultants.isEmpty() || !admins.isEmpty())) throw forbidden();
    if (admins.isEmpty() && (!users.isEmpty() || !consultants.isEmpty())) {
      var ordinary = users.isEmpty() ? consultants.getFirst() : users.getFirst();
      if (ordinary.get("delete_date") != null
          || !Objects.equals(tenant, number(ordinary.get("tenant_id")))) throw forbidden();
      return new Owner(
          users.isEmpty() ? Kind.CONSULTANT : Kind.USER, false, email(ordinary.get("email")), null);
    }
    if (admins.size() != 1 || tenant == null) throw forbidden();
    var admin = admins.getFirst();
    if (!Objects.equals(tenant, number(admin.get("tenant_id")))) throw forbidden();
    // A consultant may deliberately share the Admin's native account. Both SQL identities
    // must remain current and in the same tenant; native permission still checks its exact profile.
    if (!consultants.isEmpty()) {
      var consultant = consultants.getFirst();
      if (consultant.get("delete_date") != null
          || !Objects.equals(tenant, number(consultant.get("tenant_id")))) throw forbidden();
    }
    Kind kind;
    if (tenant > 0 && "AGENCY".equals(admin.get("type"))) kind = Kind.AGENCY_ADMIN;
    else if (tenant > 0 && "TENANT".equals(admin.get("type"))) kind = Kind.TENANT_ADMIN;
    else if (tenant == 0 && "SUPER".equals(admin.get("type"))) kind = Kind.PLATFORM_ADMIN;
    else throw forbidden();
    return new Owner(
        kind,
        !consultants.isEmpty(),
        email(admin.get("email")),
        consultants.isEmpty() ? null : email(consultants.getFirst().get("email")));
  }

  void replaceAdminEmail(String account, Long tenant, Owner original, String desired) {
    if (!original.kind().admin() || !original.equals(require(account, tenant))) throw forbidden();
    if (jdbc.update(
            "update admin set email=? where admin_id=? and (email=? or (email is null and ? is null))",
            desired,
            account,
            original.email(),
            original.email())
        != 1) throw forbidden();
    if (original.consultant()
        && jdbc.update(
                "update consultant set email=? where consultant_id=? and (email=? or (email is null and ? is null))",
                desired,
                account,
                original.consultantEmail(),
                original.consultantEmail())
            != 1) throw forbidden();
  }

  private static String email(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text) || text.length() > 320) throw forbidden();
    return text;
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
