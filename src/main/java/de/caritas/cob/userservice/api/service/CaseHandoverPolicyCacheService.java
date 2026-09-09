package de.caritas.cob.userservice.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-safe last-known-good cache for the TenantService-owned Case Handover policy. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverPolicyCacheService {

  private final @NonNull TenantCaseHandoverPolicyCacheRepository repository;
  private final @NonNull TenantCaseHandoverPolicyReadClient tenantPolicyReadClient;
  private final @NonNull ScheduledTaskClaimService scheduledTaskClaimService;
  private final @NonNull Clock clock;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CaseHandoverPolicies getEffective(Long tenantId) {
    // An unreadable snapshot is not a usable snapshot. Falling through to refresh is what the
    // last-known-good contract promises; propagating the deserialize failure here would instead
    // surface as "Cached Case Handover policy is invalid" and hide the real state from the caller.
    return repository
        .findById(tenantId)
        .flatMap(this::deserializeQuietly)
        .orElseGet(() -> refresh(tenantId));
  }

  /**
   * Refreshes one tenant. A failed upstream call never replaces the persisted last-known-good value
   * with a permissive default; it marks that snapshot stale and keeps enforcing it.
   */
  @Transactional
  public CaseHandoverPolicies refresh(Long tenantId) {
    var existing = repository.findById(tenantId);
    if (!scheduledTaskClaimService.tryClaim(
        "case-handover-policy-refresh-" + tenantId, Duration.ofMinutes(1))) {
      return existing
          .map(this::deserialize)
          .orElseThrow(
              () -> new IllegalStateException("Case Handover policy refresh already in progress"));
    }
    try {
      var response = tenantPolicyReadClient.getTenantPermissionPolicies(tenantId);
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
          TenantCaseHandoverPolicyReadClient.failureSummary(exception));
      // The snapshot itself may be unreadable. Rethrowing the ORIGINAL upstream failure keeps the
      // real cause visible; a deserialize error raised from inside this catch block would escape
      // refresh entirely and defeat the fallback this block exists to provide.
      return deserializeQuietly(cache)
          .orElseThrow(
              () -> {
                log.error(
                    "Tenant {} Case Handover policy snapshot is unreadable; no enforceable policy",
                    tenantId);
                return exception;
              });
    }
  }

  @Scheduled(fixedDelayString = "${case.handover.policy-cache-refresh-delay-ms:300000}")
  public void refreshKnownTenants() {
    // Per-tenant isolation: one tenant's failure must not abort the sweep and leave every tenant
    // after it silently enforcing an aging snapshot.
    repository
        .findAll()
        .forEach(
            cache -> {
              Long tenantId = cache.getTenantId();
              try {
                refresh(tenantId);
              } catch (RuntimeException exception) {
                log.warn(
                    "Tenant {} Case Handover policy refresh skipped in scheduled sweep: {}",
                    tenantId,
                    TenantCaseHandoverPolicyReadClient.failureSummary(exception));
              }
            });
  }

  /** Empty when the stored snapshot cannot be read back, never an exception. */
  private Optional<CaseHandoverPolicies> deserializeQuietly(TenantCaseHandoverPolicyCache cache) {
    try {
      return Optional.of(deserialize(cache));
    } catch (RuntimeException exception) {
      log.warn(
          "Tenant {} cached Case Handover policy could not be read ({})",
          cache.getTenantId(),
          exception.getClass().getSimpleName());
      return Optional.empty();
    }
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
