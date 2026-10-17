package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.service.TenantCaseHandoverPolicyReadClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The recipient scope that applies to one Träger, read from the TenantService where the setting is
 * resolved (platform preset, Träger override, requirement or recommendation).
 *
 * <p>The answer is remembered for a short time because the scope is consulted on the hot path that
 * decides which group chats a counsellor may open. A failed read is never remembered and never
 * widens access: it reads as {@link InternalGroupChatRecipientScope#AGENCY}, the behaviour before
 * the setting existed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalGroupChatRecipientScopeResolver {

  private static final Duration REMEMBERED_FOR = Duration.ofMinutes(1);

  private final @NonNull TenantCaseHandoverPolicyReadClient tenantPolicies;
  private final @NonNull Clock clock;
  private final ConcurrentHashMap<Long, Remembered> remembered = new ConcurrentHashMap<>();

  public InternalGroupChatRecipientScope resolve(Long tenantId) {
    if (tenantId == null || tenantId <= 0) {
      return InternalGroupChatRecipientScope.AGENCY;
    }
    var now = Instant.now(clock);
    var known = remembered.get(tenantId);
    if (known != null && now.isBefore(known.until())) {
      return known.scope();
    }
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
      return InternalGroupChatRecipientScope.AGENCY;
    }
  }

  private record Remembered(InternalGroupChatRecipientScope scope, Instant until) {}
}
