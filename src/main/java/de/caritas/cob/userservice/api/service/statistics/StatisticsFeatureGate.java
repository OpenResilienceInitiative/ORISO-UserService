package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Honours the per-organisation statistics switch ({@code featureStatisticsEnabled} of the tenant
 * settings) where statistics data is collected. Counting is an opt-in of the organisation: only an
 * explicit {@code true} allows it, so an unset switch, an unknown tenant or an unreachable
 * TenantService all mean "do not count".
 *
 * <p>The gate is asked once per counted message on the notification worker, so the answer per
 * tenant is remembered for a minute (the same short-lived pattern as {@code
 * InternalGroupChatRecipientScopeResolver}); a switch-off therefore takes effect within a minute. A
 * failed lookup is remembered as "off" for half a minute only: it is never remembered as "on", and
 * a hanging TenantService is asked about once per period instead of once per message. The regular
 * tenant cache lives 24 hours and is not used, because it would keep counting that long.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsFeatureGate {

  private static final Duration REMEMBERED_FOR = Duration.ofMinutes(1);
  private static final Duration FAILURE_REMEMBERED_FOR = Duration.ofSeconds(30);

  /** Map key of the single configured tenant, which has no tenant id. */
  private static final long SINGLE_TENANCY_KEY = -1L;

  private final @NonNull TenantService tenantService;
  private final @NonNull Clock clock;
  private final ConcurrentHashMap<Long, Remembered> remembered = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Object> reading = new ConcurrentHashMap<>();

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy;

  /**
   * @param tenantId the tenant of the data being collected; {@code null} means single tenancy
   */
  public boolean isEnabledForTenant(Long tenantId) {
    if (TenantContext.TECHNICAL_TENANT_ID.equals(tenantId) || tenantId == null && multitenancy) {
      return false;
    }
    long key = tenantId == null ? SINGLE_TENANCY_KEY : tenantId;
    var known = freshAnswer(key);
    if (known != null) {
      return known;
    }
    // One lookup per tenant at a time: the others wait for it and use its answer.
    synchronized (reading.computeIfAbsent(key, id -> new Object())) {
      known = freshAnswer(key);
      if (known != null) {
        return known;
      }
      boolean enabled;
      Duration keptFor = REMEMBERED_FOR;
      try {
        enabled = lookup(tenantId);
      } catch (RuntimeException e) {
        log.warn(
            "Could not read the statistics switch of tenant {}; not counting. Cause: {}",
            tenantId,
            e.getClass().getSimpleName());
        enabled = false;
        keptFor = FAILURE_REMEMBERED_FOR;
      }
      remembered.put(key, new Remembered(enabled, Instant.now(clock).plus(keptFor)));
      return enabled;
    }
  }

  private boolean lookup(Long tenantId) {
    RestrictedTenantDTO tenant =
        tenantId != null
            ? tenantService.getRestrictedTenantDataFresh(tenantId)
            : tenantService.getSingleTenancyTenantDataFresh();
    if (tenant == null
        || tenant.getSettings() == null
        || tenantId != null && !tenantId.equals(tenant.getId())) {
      return false;
    }
    return Boolean.TRUE.equals(tenant.getSettings().getFeatureStatisticsEnabled());
  }

  private Boolean freshAnswer(long key) {
    var known = remembered.get(key);
    return known != null && Instant.now(clock).isBefore(known.until()) ? known.enabled() : null;
  }

  private record Remembered(boolean enabled, Instant until) {}
}
