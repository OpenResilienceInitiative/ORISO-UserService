package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
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
      // Tenant zero is intentional here: platform administrators are people too.
      var tenant =
          tenantId == null
              ? api.getRestrictedSingleTenancyTenantData()
              : api.getRestrictedTenantDataByTenantId(tenantId);
      var policy = tenant.getSettings().getTenantAdminControls().getAccountInactivitySettings();
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
