package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.TenantCaseHandoverPolicyReadClient;
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
 * The calendar settings that apply to one Träger, read from the TenantService where the setting is
 * resolved (ADR-032). Works like {@code InternalGroupChatRecipientScopeResolver}: the answer is
 * remembered for a minute, a failed read reads as {@link CalendarPolicy#CLOSED} and is remembered
 * for a few seconds only, so an outage never widens access and is never hammered.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalendarPolicyResolver {

  private static final Duration REMEMBERED_FOR = Duration.ofMinutes(1);
  private static final Duration FAILURE_REMEMBERED_FOR = Duration.ofSeconds(10);

  private final @NonNull TenantCaseHandoverPolicyReadClient tenantPolicies;
  private final @NonNull TenantService tenantService;
  private final @NonNull Clock clock;
  private final ConcurrentHashMap<Long, Remembered> remembered = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Object> reading = new ConcurrentHashMap<>();

  @Value("${multitenancy.enabled:true}")
  private boolean multitenancy;

  private volatile RememberedTenant singleTenant;

  public CalendarPolicy resolve(Long tenantId) {
    if (tenantId == null && !multitenancy) {
      // Without multitenancy nobody has a tenant id; the one configured tenant decides.
      tenantId = singleTenantId();
    }
    if (tenantId == null || tenantId <= 0) {
      return CalendarPolicy.CLOSED;
    }
    var known = freshAnswer(tenantId);
    if (known != null) {
      return known;
    }
    // One read per Traeger at a time: the others wait for it and use its answer.
    synchronized (reading.computeIfAbsent(tenantId, id -> new Object())) {
      known = freshAnswer(tenantId);
      if (known != null) {
        return known;
      }
      var now = Instant.now(clock);
      try {
        var policy =
            CalendarPolicy.fromPolicies(tenantPolicies.getTenantPermissionPoliciesRaw(tenantId));
        remembered.put(tenantId, new Remembered(policy, now.plus(REMEMBERED_FOR)));
        return policy;
      } catch (RuntimeException failure) {
        log.warn(
            "Calendar policy of tenant {} could not be read; the closed default applies: {}",
            tenantId,
            TenantCaseHandoverPolicyReadClient.failureSummary(failure));
        remembered.put(
            tenantId, new Remembered(CalendarPolicy.CLOSED, now.plus(FAILURE_REMEMBERED_FOR)));
        return CalendarPolicy.CLOSED;
      }
    }
  }

  /** The id of the single configured tenant, remembered like the policy; null when unreadable. */
  private Long singleTenantId() {
    var known = singleTenant;
    var now = Instant.now(clock);
    if (known != null && now.isBefore(known.until())) {
      return known.id();
    }
    Long id = null;
    var keptFor = FAILURE_REMEMBERED_FOR;
    try {
      var tenant = tenantService.getSingleTenancyTenantDataFresh();
      id = tenant == null ? null : tenant.getId();
      if (id != null) {
        keptFor = REMEMBERED_FOR;
      }
    } catch (RuntimeException failure) {
      log.warn(
          "Single tenant could not be read; the closed calendar default applies: {}",
          TenantCaseHandoverPolicyReadClient.failureSummary(failure));
    }
    singleTenant = new RememberedTenant(id, now.plus(keptFor));
    return id;
  }

  private CalendarPolicy freshAnswer(Long tenantId) {
    var known = remembered.get(tenantId);
    return known != null && Instant.now(clock).isBefore(known.until()) ? known.policy() : null;
  }

  private record RememberedTenant(Long id, Instant until) {}

  private record Remembered(CalendarPolicy policy, Instant until) {}
}
