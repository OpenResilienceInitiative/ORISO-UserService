package de.caritas.cob.userservice.api.service.chat;

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
 * The recipient scope that applies to one Träger, read from the TenantService where the setting is
 * resolved (platform preset, Träger override, requirement or recommendation).
 *
 * <p>The answer is remembered for a short time because the scope is consulted on the hot path that
 * decides which group chats a counsellor may open. A failed read reads as {@link
 * InternalGroupChatRecipientScope#AGENCY}, the behaviour before the setting existed, and is
 * remembered for a few seconds only, so it never widens access and never hammers a TenantService
 * that is down.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalGroupChatRecipientScopeResolver {

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

  public InternalGroupChatRecipientScope resolve(Long tenantId) {
    if (tenantId == null && !multitenancy) {
      // Without multitenancy nobody has a tenant id; the one configured tenant decides (the same
      // fallback the DPA gate, the chat-recovery policy and the group-chat gate use).
      tenantId = singleTenantId();
    }
    if (tenantId == null || tenantId <= 0) {
      return InternalGroupChatRecipientScope.AGENCY;
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
        var scope =
            InternalGroupChatRecipientScope.fromPolicies(
                tenantPolicies.getTenantPermissionPoliciesRaw(tenantId));
        remembered.put(tenantId, new Remembered(scope, now.plus(REMEMBERED_FOR)));
        return scope;
      } catch (RuntimeException failure) {
        log.warn(
            "Recipient scope of tenant {} could not be read; counselling centre only applies: {}",
            tenantId,
            TenantCaseHandoverPolicyReadClient.failureSummary(failure));
        // Only the narrowest answer is kept, and only briefly: an outage is not hammered with
        // one login and one call per request, and it never widens access.
        remembered.put(
            tenantId,
            new Remembered(
                InternalGroupChatRecipientScope.AGENCY, now.plus(FAILURE_REMEMBERED_FOR)));
        return InternalGroupChatRecipientScope.AGENCY;
      }
    }
  }

  /** The id of the single configured tenant, remembered like the scope; null when unreadable. */
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
          "Single tenant could not be read; counselling centre only applies: {}",
          TenantCaseHandoverPolicyReadClient.failureSummary(failure));
    }
    singleTenant = new RememberedTenant(id, now.plus(keptFor));
    return id;
  }

  private InternalGroupChatRecipientScope freshAnswer(Long tenantId) {
    var known = remembered.get(tenantId);
    return known != null && Instant.now(clock).isBefore(known.until()) ? known.scope() : null;
  }

  private record RememberedTenant(Long id, Instant until) {}

  private record Remembered(InternalGroupChatRecipientScope scope, Instant until) {}
}
