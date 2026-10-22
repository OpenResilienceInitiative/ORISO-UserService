package de.caritas.cob.userservice.api.service.identity;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual application authorization and finite profile roll-forward; no native SDK authority. */
public final class HumanAdminAccountMaintenance {
  public HumanAdminAccountMaintenance(
      IdentityClientConfig mode,
      AuthenticatedUser actor,
      AdminScope scope,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      HttpServletRequest request,
      HumanAdminMaintenanceTransport transport,
      Clock clock,
      String publicIssuer) {
    Objects.requireNonNull(mode);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(jdbc);
    Objects.requireNonNull(transactions);
    Objects.requireNonNull(request);
    Objects.requireNonNull(transport);
    Objects.requireNonNull(clock);
    Objects.requireNonNull(publicIssuer);
  }

  public HumanAdminMaintenanceTransport.Projection read(String accountId) {
    throw new IllegalStateException("Checked admin maintenance is unavailable");
  }

  public void updateAdminProfile(String accountId, IdentityProfileUpdate profile) {
    throw new IllegalStateException("Checked admin maintenance is unavailable");
  }

  /** A public target, SQL entity or signed tuple cannot construct this private live handle. */
  public static final class CheckedAccount {
    private CheckedAccount() {}

    public String accountId() { return null; }
    public Long tenantId() { return null; }
    public String humanSubject() { return null; }
    public String humanIssuer() { return null; }
    public String humanClient() { return null; }
    public String rawHumanBearer() { return null; }
    public UUID workflowId() { return null; }
    public void requireCurrent() {
      throw new IllegalStateException("Checked admin maintenance is unavailable");
    }

    @Override
    public String toString() {
      return "CheckedAccount[authorization=redacted]";
    }
  }
}
