package de.caritas.cob.userservice.api.service.statistics;

import de.caritas.cob.userservice.api.model.CommunicationStat;
import de.caritas.cob.userservice.api.model.CommunicationStat.Kind;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CommunicationStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records advice-seeker messages and calls for the admin statistics dashboard. No message content
 * and no identity is stored (KDG): a row says only where (tenant, agency, session) and when
 * something happened. The same switches as the counsellor message count apply: the global
 * message-count setting and the organisation's statistics switch.
 *
 * <p>Best-effort: this runs next to the live-notification path, so every failure is logged and
 * swallowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunicationStatService {

  private final @NonNull CommunicationStatRepository communicationStatRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull StatisticsSettingsService statisticsSettingsService;
  private final @NonNull StatisticsFeatureGate statisticsFeatureGate;
  private final @NonNull Clock clock;

  /**
   * One message whose sender resolved to an advice seeker, in a room mapped to a session. Counted
   * once per Matrix event id: a sync batch is delivered again when a later event in it fails.
   */
  public void recordAdviceSeekerMessage(Long sessionId, String eventId) {
    record(
        Kind.ADVICE_SEEKER_MESSAGE,
        sessionId,
        eventId == null || eventId.isBlank() ? null : eventKey(eventId));
  }

  /** One call, counted once per Matrix call id however often its invite is seen. */
  public void recordCall(Long sessionId, String callId, boolean video) {
    if (callId == null || callId.isBlank()) {
      return;
    }
    record(video ? Kind.VIDEO_CALL : Kind.AUDIO_CALL, sessionId, eventKey(callId));
  }

  private void record(Kind kind, Long sessionId, String eventKey) {
    try {
      if (sessionId == null || !statisticsSettingsService.isMessageCountTrackingEnabled()) {
        return;
      }
      Session session = sessionRepository.findById(sessionId).orElse(null);
      if (session == null || !statisticsFeatureGate.isEnabledForTenant(session.getTenantId())) {
        return;
      }
      if (eventKey != null && communicationStatRepository.existsByEventKey(eventKey)) {
        return;
      }
      communicationStatRepository.save(
          CommunicationStat.builder()
              .tenantId(session.getTenantId())
              .agencyId(session.getAgencyId())
              .sessionId(sessionId)
              .kind(kind)
              .occurredAt(LocalDateTime.now(clock))
              .eventKey(eventKey)
              .build());
    } catch (Exception e) {
      // A duplicate event key from a concurrent writer also lands here: it is already counted.
      log.error("Failed to record {} communication statistic", kind, e);
    }
  }

  /** SHA-256 of a Matrix event id or call id; the id itself is never stored. */
  static String eventKey(String matrixId) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(matrixId.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
