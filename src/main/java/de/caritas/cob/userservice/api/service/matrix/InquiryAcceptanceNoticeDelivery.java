package de.caritas.cob.userservice.api.service.matrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice;
import de.caritas.cob.userservice.api.model.InquiryAcceptanceNotice.DeliveryState;
import de.caritas.cob.userservice.api.port.out.InquiryAcceptanceNoticeRepository;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Existing private-room system-notification transport; no bot or extra room member is introduced.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InquiryAcceptanceNoticeDelivery {
  public static final String TYPE = "INQUIRY_ACCEPTED";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final InquiryAcceptanceNoticeRepository notices;
  private final InquiryAcceptanceNoticeStore store;
  private final MatrixSynapseService matrix;

  public void dispatchPending() {
    for (var notice :
        notices
            .findTop100ByDeliveryStateAndNextAttemptAtUtcLessThanEqualOrderByNextAttemptAtUtcAscSessionIdAsc(
                DeliveryState.PENDING, java.time.LocalDateTime.now(ZoneOffset.UTC))) {
      dispatchSafely(notice.getSessionId());
    }
  }

  public void dispatchSafely(Long sessionId) {
    try {
      dispatch(sessionId);
    } catch (RuntimeException exception) {
      try {
        store.deferPending(sessionId);
      } catch (RuntimeException deferException) {
        log.warn(
            "Could not defer initial acceptance notice for session {} ({})",
            sessionId,
            deferException.getClass().getSimpleName());
      }
      log.warn(
          "Initial acceptance notice for session {} remains pending/uncertain ({})",
          sessionId,
          exception.getClass().getSimpleName());
    }
  }

  public void dispatch(Long sessionId) {
    var notice = notices.findById(sessionId).orElse(null);
    if (notice == null || notice.getDeliveryState() != DeliveryState.PENDING) return;
    // Token minting is before the claim: failures here are safe to retry, and no token is stored.
    String body = body(notice);
    var token = matrix.loginAsUserAccessToken(notice.getSenderMatrixId());
    if (token == null || token.isBlank()) {
      store.deferPending(sessionId);
      return;
    }
    if (!store.claimForSend(sessionId)) return;
    var result =
        matrix.sendMessage(notice.getMatrixRoomId(), body, token, "inquiry-accepted-" + sessionId);
    if (result != null
        && result.get("event_id") instanceof String eventId
        && !eventId.isBlank()
        && !result.containsKey("error")) {
      store.acknowledge(sessionId, eventId);
    } else {
      log.warn(
          "Initial acceptance notice for session {} has an uncertain Matrix handoff; automatic resend disabled",
          sessionId);
    }
  }

  String body(InquiryAcceptanceNotice notice) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", TYPE);
    payload.put("title", notice.getTitle());
    payload.put("description", notice.getDescription());
    if (notice.getPublicAdvisorName() != null && !notice.getPublicAdvisorName().isBlank())
      payload.put("username", notice.getPublicAdvisorName());
    payload.put(
        "acceptance",
        Map.of(
            "sessionId",
            notice.getSessionId(),
            "acceptedAt",
            notice.getAcceptedAtUtc().toInstant(ZoneOffset.UTC).toString()));
    try {
      return MatrixSessionSystemMessageService.SYSTEM_NOTIFICATION_PREFIX
          + JSON.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalStateException("Cannot encode initial acceptance notice", exception);
    }
  }
}
