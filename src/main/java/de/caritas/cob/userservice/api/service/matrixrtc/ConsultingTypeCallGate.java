package de.caritas.cob.userservice.api.service.matrixrtc;

import de.caritas.cob.userservice.api.service.ConsultingTypeService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The switch an admin sets per form of counselling ("Videoanrufe erlauben", {@code
 * isVideoCallAllowed}, ORISO-UserService#1564). The counselling app hides its whole call menu,
 * audio and video, for a form of counselling that does not allow calls; without this gate the call
 * policy that Element Call and LiveKit ask would still say yes to a modified client.
 *
 * <p>It narrows what {@link MatrixRtcCallPolicyService} granted (the Träger switches per chat
 * type), it never widens it. A supervision room is not gated: its calls are staff to staff and the
 * app does not apply this switch there either (product decision 12.09.2026).
 *
 * <p>The setting is read fresh from the ConsultingTypeService, never from the one-day cache, in the
 * Träger of the conversation. A value that is not {@code true} (switched off, missing) closes the
 * calls, as the app does; so does a form of counselling that cannot be read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultingTypeCallGate {

  private final @NonNull MatrixRtcPolicyContextResolver contextResolver;
  private final @NonNull ConsultingTypeService consultingTypeService;

  /**
   * @param granted what the call policy allows so far
   * @param sourceRoomId the conversation the call belongs to
   * @return {@code granted}, or {@link CallMediaPolicy#denied()} when the form of counselling does
   *     not allow calls
   */
  public CallMediaPolicy restrict(CallMediaPolicy granted, String sourceRoomId) {
    if (!granted.audioAllowed() && !granted.videoAllowed()) {
      return granted;
    }
    // Same cross-Träger room lookup as the call policy: the callback has no tenant of its own.
    var context = TenantContext.supplyAcrossTenants(() -> contextResolver.resolve(sourceRoomId));
    if (context.isEmpty()) {
      return deny(CallPolicyDenialReason.NO_TENANT_CONTEXT, null);
    }
    var conversation = context.get();
    if (conversation.chatType() == MatrixRtcPolicyContext.ChatType.SUPERVISION) {
      return granted;
    }
    if (conversation.consultingTypeId() == null || conversation.tenantId() == null) {
      return deny(CallPolicyDenialReason.CONSULTING_TYPE_UNAVAILABLE, conversation.tenantId());
    }
    Boolean callsAllowed = readCallsAllowed(conversation);
    if (callsAllowed == null) {
      return deny(CallPolicyDenialReason.CONSULTING_TYPE_UNAVAILABLE, conversation.tenantId());
    }
    return callsAllowed
        ? granted
        : deny(CallPolicyDenialReason.CONSULTING_TYPE_CALLS_DISABLED, conversation.tenantId());
  }

  /** {@code null} when the form of counselling cannot be read; {@code false} for off or unset. */
  private Boolean readCallsAllowed(MatrixRtcPolicyContext conversation) {
    var answer = new AtomicReference<Boolean>();
    try {
      TenantContext.runIn(
          conversation.tenantId(),
          () -> {
            var consultingType =
                consultingTypeService.getFreshExtendedConsultingTypeResponseDTO(
                    conversation.consultingTypeId());
            if (consultingType != null) {
              answer.set(Boolean.TRUE.equals(consultingType.getIsVideoCallAllowed()));
            }
          });
    } catch (RuntimeException unavailable) {
      log.warn(
          "Call policy consulting type lookup failed for tenant {}: {}",
          conversation.tenantId(),
          unavailable.getClass().getSimpleName());
      return null;
    }
    return answer.get();
  }

  private CallMediaPolicy deny(CallPolicyDenialReason reason, Long tenantId) {
    // Like the call policy, no room or user id in the log: a reason code and the Träger only.
    log.info("Call policy denied: reason={}, tenant={}", reason, tenantId);
    return CallMediaPolicy.denied();
  }
}
