package de.caritas.cob.userservice.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-safe last-known-good cache for the TenantService-owned Case Handover policy. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverPolicyCacheService {

  private final @NonNull TenantCaseHandoverPolicyCacheRepository repository;
  private final @NonNull TenantAdminServiceApiControllerFactory tenantServiceFactory;
  private final @NonNull ScheduledTaskClaimService scheduledTaskClaimService;
  private final @NonNull Clock clock;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${case.handover.policy-refresh-claim-duration:PT1M}")
  private Duration policyRefreshClaimDuration = Duration.ofMinutes(1);

  @Transactional
  public CaseHandoverPolicies getEffective(Long tenantId) {
    return repository.findById(tenantId).map(this::deserialize).orElseGet(() -> refresh(tenantId));
  }

  /**
   * Refreshes one tenant. A failed upstream call never replaces the persisted last-known-good value
   * with a permissive default; it marks that snapshot stale and keeps enforcing it.
   */
  @Transactional
  public CaseHandoverPolicies refresh(Long tenantId) {
    var existing = repository.findById(tenantId);
    if (!scheduledTaskClaimService.tryClaim(
        "case-handover-policy-refresh-" + tenantId, policyRefreshClaimDuration)) {
      return existing
          .map(this::deserialize)
          .orElseThrow(
              () ->
                  new ServiceUnavailableException(
                      "Case Handover policy refresh already in progress; retry shortly"));
    }
    try {
      var response =
          tenantServiceFactory.createControllerApi().getTenantPermissionPolicies(tenantId);
      if (response == null
          || !tenantId.equals(response.getTenantId())
          || response.getCaseHandoverPolicies() == null) {
        throw new IllegalStateException("TenantService returned no matching Case Handover policy");
      }
      var cache = existing.orElseGet(TenantCaseHandoverPolicyCache::new);
      cache.setTenantId(tenantId);
      cache.setPolicies(serialize(response.getCaseHandoverPolicies()));
      cache.setRefreshedAt(LocalDateTime.now(clock));
      cache.setStaleSince(null);
      repository.save(cache);
      return response.getCaseHandoverPolicies();
    } catch (RuntimeException exception) {
      if (existing.isEmpty()) {
        throw exception;
      }
      var cache = existing.get();
      if (cache.getStaleSince() == null) {
        cache.setStaleSince(LocalDateTime.now(clock));
        repository.save(cache);
      }
      log.warn(
          "Tenant {} Case Handover policy refresh failed; enforcing last-known-good snapshot: {}",
          tenantId,
          exception.getMessage());
      return deserialize(cache);
    }
  }

  @Scheduled(fixedDelayString = "${case.handover.policy-cache-refresh-delay-ms:300000}")
  @Transactional
  void refreshKnownTenants() {
    repository.findAll().forEach(cache -> refresh(cache.getTenantId()));
  }

  private String serialize(CaseHandoverPolicies policies) {
    try {
      return objectMapper.writeValueAsString(policies);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Case Handover policy could not be cached", exception);
    }
  }

  private CaseHandoverPolicies deserialize(TenantCaseHandoverPolicyCache cache) {
    try {
      return objectMapper.readValue(cache.getPolicies(), CaseHandoverPolicies.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cached Case Handover policy is invalid", exception);
    }
  }
}
