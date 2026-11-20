package de.caritas.cob.userservice.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.TenantCaseHandoverPolicyCache;
import de.caritas.cob.userservice.api.port.out.TenantCaseHandoverPolicyCacheRepository;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantPermissionPolicies;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-safe last-known-good cache for the TenantService-owned Case Handover policy. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverPolicyCacheService {

  private final @NonNull TenantCaseHandoverPolicyCacheRepository repository;
  private final @NonNull TenantCaseHandoverPolicyReadClient tenantPolicyReadClient;
  private final @NonNull ScheduledTaskClaimService scheduledTaskClaimService;
  private final @NonNull Clock clock;
  private final @NonNull PlatformTransactionManager transactionManager;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${case.handover.policy-refresh-claim-duration:PT1M}")
  private Duration policyRefreshClaimDuration = Duration.ofMinutes(1);

  /**
   * One fresh, complete authority for a runtime action; only a validated full snapshot may replace
   * an unavailable provider. Legacy reason-only snapshots remain configuration-readable.
   */
  public CurrentPolicy getCurrent(Long tenantId) {
    if (tenantId == null || tenantId <= 0) {
      throw new ServiceUnavailableException("A tenant is required for Case Handover policy");
    }
    TenantPermissionPolicies response;
    try {
      response = tenantPolicyReadClient.getTenantPermissionPolicies(tenantId);
    } catch (TenantCaseHandoverPolicyReadClient.InvalidPolicyResponseException invalid) {
      throw new ServiceUnavailableException("Tenant Case Handover policy response is invalid");
    } catch (RuntimeException unavailable) {
      log.warn(
          "Tenant {} current Case Handover policy unavailable: {}",
          tenantId,
          TenantCaseHandoverPolicyReadClient.failureSummary(unavailable));
      Optional<TenantCaseHandoverPolicyCache> existing;
      try {
        existing = inNewTransaction(() -> repository.findById(tenantId));
      } catch (DataAccessException | TransactionException cacheUnavailable) {
        throw new ServiceUnavailableException("Tenant Case Handover policy is unavailable");
      }
      if (existing.isEmpty()) {
        throw new ServiceUnavailableException("Tenant Case Handover policy is unavailable");
      }
      var cache = existing.get();
      CurrentPolicy fallback;
      try {
        if (!tenantId.equals(cache.getTenantId())) throw new IllegalStateException();
        fallback =
            validateCurrent(
                tenantId,
                objectMapper.readValue(cache.getPolicies(), TenantPermissionPolicies.class));
      } catch (RuntimeException | JsonProcessingException invalid) {
        throw new ServiceUnavailableException(
            "No complete tenant Case Handover snapshot is available");
      }
      try {
        markStale(cache);
      } catch (DataAccessException | TransactionException staleWriteFailed) {
        log.warn(
            "Tenant {} stale marker for Case Handover snapshot not persisted: {}",
            tenantId,
            staleWriteFailed.getClass().getSimpleName());
      }
      return fallback;
    }
    // A malformed live authority must not fall back to an older permissive snapshot.
    CurrentPolicy current = validateCurrent(tenantId, response);
    String serialized = serialize(response);
    try {
      persistSnapshot(tenantId, serialized);
    } catch (DataAccessException | TransactionException cacheUnavailable) {
      // A cache write (including a concurrent first insert) cannot override fresh authority.
      log.warn(
          "Tenant {} live Case Handover policy cache write failed: {}",
          tenantId,
          cacheUnavailable.getClass().getSimpleName());
    }
    return current;
  }

  public record CurrentPolicy(boolean enabled, CaseHandoverPolicies policies) {
    public void requireEnabled() {
      if (!enabled)
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
            "Case Handover is disabled for this tenant");
    }
  }

  private CurrentPolicy validateCurrent(Long tenantId, TenantPermissionPolicies response) {
    var master =
        response == null || response.getPolicies() == null
            ? null
            : response.getPolicies().get("caseHandoverEnabled");
    if (response == null
        || !tenantId.equals(response.getTenantId())
        || master == null
        || master.getValue() == null
        || master.getMode() == null
        || response.getCaseHandoverPolicies() == null
        || response.getCaseHandoverPolicies().getReasons() == null
        || response.getCaseHandoverPolicies().getReasons().values().stream()
            .anyMatch(policy -> policy == null || policy.getCode() == null)) {
      throw new ServiceUnavailableException("No complete tenant Case Handover policy is available");
    }
    return new CurrentPolicy(master.getValue(), response.getCaseHandoverPolicies());
  }

  private void markStale(TenantCaseHandoverPolicyCache cache) {
    if (cache.getStaleSince() != null) return;
    inNewTransaction(
        () -> {
          repository
              .findById(cache.getTenantId())
              .ifPresent(
                  current -> {
                    if (current.getStaleSince() == null) {
                      current.setStaleSince(LocalDateTime.now(clock));
                      repository.save(current);
                    }
                  });
          return null;
        });
  }

  public CaseHandoverPolicies getEffective(Long tenantId) {
    // An unreadable snapshot is not a usable snapshot. Falling through to refresh is what the
    // last-known-good contract promises; propagating the deserialize failure here would instead
    // surface as "Cached Case Handover policy is invalid" and hide the real state from the caller.
    return inNewTransaction(() -> repository.findById(tenantId))
        .flatMap(this::deserializeQuietly)
        .orElseGet(() -> refresh(tenantId));
  }

  /**
   * Refreshes one tenant. A failed upstream call never replaces the persisted last-known-good value
   * with a permissive default; it marks that snapshot stale and keeps enforcing it.
   */
  public CaseHandoverPolicies refresh(Long tenantId) {
    var existing = inNewTransaction(() -> repository.findById(tenantId));
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
      var response = tenantPolicyReadClient.getTenantPermissionPolicies(tenantId);
      validateCurrent(tenantId, response);
      putEffective(tenantId, response);
      return response.getCaseHandoverPolicies();
    } catch (RuntimeException exception) {
      if (existing.isEmpty()) {
        log.warn(
            "Tenant {} Case Handover policy refresh failed without a usable snapshot: {}",
            tenantId,
            TenantCaseHandoverPolicyReadClient.failureSummary(exception));
        throw new ServiceUnavailableException("Tenant Case Handover policy is unavailable");
      }
      var cache = existing.get();
      if (cache.getStaleSince() == null) {
        inNewTransaction(
            () -> {
              repository
                  .findById(tenantId)
                  .ifPresent(
                      current -> {
                        if (current.getStaleSince() == null) {
                          current.setStaleSince(LocalDateTime.now(clock));
                          repository.save(current);
                        }
                      });
              return null;
            });
        cache.setStaleSince(LocalDateTime.now(clock));
      }
      log.warn(
          "Tenant {} Case Handover policy refresh failed; enforcing last-known-good snapshot: {}",
          tenantId,
          TenantCaseHandoverPolicyReadClient.failureSummary(exception));
      // An unreadable snapshot is equivalent to having no enforceable policy. Keep the provider
      // details in sanitized logs and return the retryable API-level failure callers understand.
      return deserializeQuietly(cache)
          .orElseThrow(
              () -> {
                log.error(
                    "Tenant {} Case Handover policy snapshot is unreadable; no enforceable policy",
                    tenantId);
                return new ServiceUnavailableException(
                    "Tenant Case Handover policy is unavailable");
              });
    }
  }

  /** Persists a provider-confirmed response without another downstream round trip. */
  public CaseHandoverPolicies putEffective(Long tenantId, TenantPermissionPolicies response) {
    validateCurrent(tenantId, response);
    persistSnapshot(tenantId, serialize(response));
    return response.getCaseHandoverPolicies();
  }

  private String serialize(TenantPermissionPolicies response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException invalid) {
      throw new ServiceUnavailableException("Tenant Case Handover policy could not be cached");
    }
  }

  private void persistSnapshot(Long tenantId, String serialized) {
    inNewTransaction(
        () -> {
          var cache = repository.findById(tenantId).orElseGet(TenantCaseHandoverPolicyCache::new);
          cache.setTenantId(tenantId);
          cache.setPolicies(serialized);
          cache.setRefreshedAt(LocalDateTime.now(clock));
          cache.setStaleSince(null);
          repository.save(cache);
          return null;
        });
  }

  /**
   * Writes through to TenantService and immediately replaces the local enforcement snapshot. The
   * read-modify-write runs with the calling admin's own token, so TenantService decides whether
   * this admin may change the tenant's settings (ORISO-Helm#367).
   */
  public CaseHandoverPolicies updateEffective(Long tenantId, CaseHandoverPolicies policies) {
    TenantPermissionPolicies requestedPolicies =
        tenantPolicyReadClient.getTenantPermissionPoliciesAsCaller(tenantId);
    if (requestedPolicies == null || !tenantId.equals(requestedPolicies.getTenantId())) {
      throw new ServiceUnavailableException("Tenant Case Handover policy update failed");
    }
    requestedPolicies.setCaseHandoverPolicies(policies);
    var resolved =
        tenantPolicyReadClient.updateTenantPermissionPoliciesAsCaller(tenantId, requestedPolicies);
    if (resolved == null
        || !tenantId.equals(resolved.getTenantId())
        || resolved.getCaseHandoverPolicies() == null) {
      throw new ServiceUnavailableException("Tenant Case Handover policy update failed");
    }
    return putEffective(tenantId, resolved);
  }

  @Scheduled(fixedDelayString = "${case.handover.policy-cache-refresh-delay-ms:300000}")
  public void refreshKnownTenants() {
    // Per-tenant isolation: one tenant's failure must not abort the sweep and leave every tenant
    // after it silently enforcing an aging snapshot.
    inNewTransaction(repository::findAll)
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

  private CaseHandoverPolicies deserialize(TenantCaseHandoverPolicyCache cache) {
    try {
      var tree = objectMapper.readTree(cache.getPolicies());
      var policies =
          tree.has("caseHandoverPolicies")
              ? validateCurrent(
                      cache.getTenantId(),
                      objectMapper.treeToValue(tree, TenantPermissionPolicies.class))
                  .policies()
              : objectMapper.treeToValue(tree, CaseHandoverPolicies.class);
      if (policies == null || policies.getReasons() == null) {
        throw new IllegalStateException("Cached Case Handover policy has no reasons");
      }
      return policies;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cached Case Handover policy is invalid", exception);
    }
  }

  private <T> T inNewTransaction(java.util.function.Supplier<T> action) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(status -> action.get());
  }
}
