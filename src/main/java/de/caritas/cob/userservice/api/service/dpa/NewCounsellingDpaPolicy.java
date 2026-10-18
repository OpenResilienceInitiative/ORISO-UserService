package de.caritas.cob.userservice.api.service.dpa;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaGateStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One owner-authoritative AVV decision for new work. Begun counselling has a separate lifecycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewCounsellingDpaPolicy {
  private final TenantDpaGateReadClient owner;
  private final AgencyService agencyService;
  private final TenantService tenantService;

  @Value("${multitenancy.enabled}")
  private boolean multitenancy;

  public void requireForAgency(Long agencyId) {
    AgencyDTO agency;
    try {
      agency = agencyService.getAgencyWithoutCaching(agencyId);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    requireForAgency(agency);
  }

  public void requireForAgency(AgencyDTO agency) {
    if (agency == null) throw unavailable(null);
    requireForTenant(agency.getTenantId());
  }

  /**
   * The accepting consultant's persisted tenant must never fall back to a queue or single tenant.
   */
  public void requireForConcreteTenant(Long tenantId) {
    if (tenantId == null || tenantId <= 0) throw unavailable(null);
    requireForTenant(tenantId);
  }

  public void requireForTenant(Long tenantId) {
    DpaGateStatusDTO gate;
    try {
      if (tenantId == null && !multitenancy) {
        var tenant = tenantService.getSingleTenancyTenantDataFresh();
        tenantId = tenant == null ? null : tenant.getId();
      }
      if (tenantId == null || tenantId <= 0)
        throw new IllegalStateException("Serving tenant unavailable");
      gate = owner.read(tenantId);
      if (gate == null) throw new IllegalStateException("AVV policy unavailable");
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    if (!allowsNewWork(gate)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.DPA_NEW_COUNSELLING_NOT_ALLOWED, HttpStatus.FORBIDDEN);
    }
  }

  private boolean allowsNewWork(DpaGateStatusDTO gate) {
    if (gate.getDpaPublished() == null || gate.getDpaSigned() == null) throw unavailable(null);
    // Older owners never grant grace. Only a complete signed-only contract is compatible.
    if (gate.getSigningDeadlineAt() == null
        && gate.getRenewalGraceActive() == null
        && gate.getDpaStatus() == null
        && gate.getCurrentDpaVersion() == null
        && gate.getNewCounsellingAllowed() == null) {
      return gate.getDpaPublished() && gate.getDpaSigned();
    }
    var status = gate.getDpaStatus();
    if (status == null
        || !java.util.Set.of("VALID", "OUTDATED", "UNSIGNED", "MISSING", "INCONSISTENT")
            .contains(status)
        || gate.getRenewalGraceActive() == null
        || gate.getNewCounsellingAllowed() == null) {
      throw unavailable(null);
    }
    boolean published =
        gate.getCurrentDpaVersion() != null && !gate.getCurrentDpaVersion().isBlank();
    boolean valid = "VALID".equals(status);
    boolean grace = gate.getRenewalGraceActive();
    if (gate.getDpaPublished() != published
        || gate.getDpaSigned() != valid
        || ((valid || "OUTDATED".equals(status) || "UNSIGNED".equals(status)) && !published)
        || ("MISSING".equals(status) && published)
        || (grace && (!"OUTDATED".equals(status) || gate.getSigningDeadlineAt() == null))
        || gate.getNewCounsellingAllowed() != (valid || grace)) throw unavailable(null);
    if (gate.getSigningDeadlineAt() != null) {
      try {
        var deadline = java.time.OffsetDateTime.parse(gate.getSigningDeadlineAt());
        java.time.Instant.parse(gate.getSigningDeadlineAt());
        if (!java.time.ZoneOffset.UTC.equals(deadline.getOffset())) throw unavailable(null);
      } catch (java.time.format.DateTimeParseException failure) {
        throw unavailable(failure);
      }
    }
    // The owner decides whether the deadline has passed; no consumer clock or fabricated grace.
    return gate.getNewCounsellingAllowed();
  }

  private CustomValidationHttpStatusException unavailable(RuntimeException failure) {
    log.warn(
        "AVV policy unavailable: type={}",
        failure == null ? "InvalidContract" : failure.getClass().getSimpleName());
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.DPA_POLICY_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
  }
}
