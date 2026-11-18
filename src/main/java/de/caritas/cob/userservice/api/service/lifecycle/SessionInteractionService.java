package de.caritas.cob.userservice.api.service.lifecycle;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher.SessionInteraction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns counsellor interactions in a 1:1 session into {@code session.interaction} lifecycle events
 * (ADR-034), so a documentation add-on can remind the counsellor to document.
 *
 * <p>Messages are coalesced per session and counsellor: at most one event per {@code
 * lifecycle.events.session-interaction.message-interval}. A call is announced once per counsellor
 * who took part, even when both call tracking paths see its end. The coalescing state lives in
 * memory, so each instance coalesces on its own and a restart forgets it.
 *
 * <p>Best effort like every lifecycle event: a failure is logged and never reaches the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionInteractionService {

  public static final String MESSAGE = "message";
  public static final String CALL = "call";
  public static final String VIDEO_CALL = "video_call";

  static final Duration CALL_DEDUPLICATION_WINDOW = Duration.ofHours(12);
  private static final int EVICTION_THRESHOLD = 1024;

  private final @NonNull LifecycleEventPublisher publisher;
  private final @NonNull SessionRepository sessionRepository;
  private final Map<String, Instant> claimedUntil = new ConcurrentHashMap<>();

  @Value("${lifecycle.events.session-interaction.message-interval:PT10M}")
  private Duration messageInterval = Duration.ofMinutes(10);

  private Clock clock = Clock.systemUTC();

  /**
   * A counsellor wrote in the session. Messages older than one interval are replayed history (the
   * Matrix listener's first sync after a restart) and are ignored.
   */
  public void messageWritten(Long sessionId, String consultantId, Instant writtenAt) {
    if (sessionId == null || consultantId == null || writtenAt == null) {
      return;
    }
    var now = clock.instant();
    if (writtenAt.isBefore(now.minus(messageInterval))) {
      return;
    }
    announce(
        "message:" + sessionId + ":" + consultantId,
        messageInterval,
        sessionId,
        consultantId,
        MESSAGE,
        writtenAt);
  }

  /** A call of the session ended in which the counsellor took part. */
  public void callEnded(
      Long sessionId, String consultantId, String callId, boolean video, Instant endedAt) {
    if (sessionId == null || consultantId == null || callId == null || endedAt == null) {
      return;
    }
    announce(
        "call:" + sessionId + ":" + callId + ":" + consultantId,
        CALL_DEDUPLICATION_WINDOW,
        sessionId,
        consultantId,
        video ? VIDEO_CALL : CALL,
        endedAt);
  }

  private void announce(
      String key,
      Duration window,
      Long sessionId,
      String consultantId,
      String type,
      Instant occurredAt) {
    try {
      var session = sessionRepository.findById(sessionId).orElse(null);
      if (session == null || isGroupChat(session) || !claim(key, window)) {
        return;
      }
      releaseOnRollback(key);
      publisher.sessionInteraction(
          new SessionInteraction(
              session.getTenantId(),
              session.getId(),
              session.getAgencyId(),
              consultantId,
              type,
              occurredAt));
    } catch (Exception e) {
      log.error("Could not announce a {} interaction of session {}", type, sessionId, e);
    }
  }

  /**
   * Group chats have a shadow session as well ({@code CreateChatFacade}), but the contract covers
   * 1:1 sessions only.
   */
  private static boolean isGroupChat(Session session) {
    return session.getConversationType() == ConversationType.INTERNAL_GROUP
        || session.getConversationType() == ConversationType.SELF_HELP;
  }

  private boolean claim(String key, Duration window) {
    var now = clock.instant();
    if (claimedUntil.size() > EVICTION_THRESHOLD) {
      claimedUntil.values().removeIf(until -> !until.isAfter(now));
    }
    var claimed = new boolean[1];
    claimedUntil.compute(
        key,
        (k, until) -> {
          if (until != null && until.isAfter(now)) {
            return until;
          }
          claimed[0] = true;
          return now.plus(window);
        });
    return claimed[0];
  }

  /** The event is sent after the commit; a rolled back interaction must not block the replay. */
  private void releaseOnRollback(String key) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              claimedUntil.remove(key);
            }
          }
        });
  }
}
