package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.AccountInactivitySettings;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolves current creation defaults over the uncached public tenant contract. */
@Service
@RequiredArgsConstructor
public class AccountInactivityEnrollmentService {
  public enum Group {
    ASKER,
    CONSULTANT,
    OTHER
  }

  public record Policy(int months, long revision, java.time.Instant capturedAt) {}

  private final TenantServiceApiControllerFactory tenantApi;
  private final AccountInactivityService lifecycle;
  private final Clock clock;
  private final SecurityHeaderSupplier securityHeaderSupplier;

  @Transactional
  public void enroll(String identityId, Long tenantId, Group group) {
    if (lifecycle.snapshot(identityId).isPresent()) return;
    enroll(identityId, tenantId, capture(tenantId, group));
  }

  @Transactional
  public void enroll(String identityId, Long tenantId, Policy policy) {
    lifecycle.assignAtCreation(
        identityId, tenantId, policy.months(), policy.revision(), policy.capturedAt());
  }

  @Transactional
  public void discardUncompletedCreation(String identityId, Policy policy) {
    lifecycle.discardUncompletedCreation(
        identityId, policy.months(), policy.revision(), policy.capturedAt());
  }

  public Policy capture(Long tenantId, Group group) {
    try {
      var api = tenantApi.createControllerApi();
      AccountInactivitySettings policy;
      if (TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
        // Platform administrators have no ordinary tenant record. Read their defaults with the
        // caller's rights, never an ambient service identity, and still enroll each person.
        securityHeaderSupplier
            .getCallerKeycloakAndCsrfHttpHeaders()
            .forEach(
                (name, values) -> api.getApiClient().addDefaultHeader(name, values.getFirst()));
        policy = api.getTenantAdminControls().getAccountInactivitySettings();
      } else {
        var tenant =
            tenantId == null
                ? api.getRestrictedSingleTenancyTenantData()
                : api.getRestrictedTenantDataByTenantId(tenantId);
        policy = tenant.getSettings().getTenantAdminControls().getAccountInactivitySettings();
      }
      Integer months =
          switch (group) {
            case ASKER -> policy.getAskerMonths();
            case CONSULTANT -> policy.getConsultantMonths();
            case OTHER -> policy.getOtherMonths();
          };
      if (months == null || months < 1 || policy.getRevision() == null || policy.getRevision() < 0)
        throw new IllegalStateException("Invalid account inactivity policy");
      return new Policy(
          months,
          policy.getRevision(),
          clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    } catch (RuntimeException failure) {
      // Upstream exceptions can contain credentials or response bodies: do not propagate them.
      org.apache.commons.logging.LogFactory.getLog(getClass())
          .warn("Account inactivity policy unavailable: " + failure.getClass().getSimpleName());
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Account inactivity policy unavailable");
    }
  }
}
