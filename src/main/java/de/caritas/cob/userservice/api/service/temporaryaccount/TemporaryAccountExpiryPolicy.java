package de.caritas.cob.userservice.api.service.temporaryaccount;

import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * When a direct-link temporary account is due for deletion (ADR-029 §4). Uses only the values the
 * account copied at creation.
 *
 * <ul>
 *   <li>Call: the snapshotted minutes after the last hang-up; never later than the snapshotted
 *       maximum (hard cap 24 h) after creation, even while a call is still running.
 *   <li>Chat: when the counsellor closes or archives the conversation, or after the snapshotted
 *       days without activity (counted from creation when nothing happened yet).
 * </ul>
 */
@Component
public class TemporaryAccountExpiryPolicy {

  /**
   * @param closed the conversation is DONE or archived
   * @param lastActivityAt the latest change of the conversation, or null
   * @param lastCallEndedAt the latest hang-up in the conversation, or null
   * @param callRunning a call in the conversation has not ended yet
   */
  public record ConversationFacts(
      boolean closed,
      LocalDateTime lastActivityAt,
      LocalDateTime lastCallEndedAt,
      boolean callRunning) {}

  public boolean isDue(
      TemporaryAccountSnapshot snapshot,
      LocalDateTime createdAt,
      ConversationFacts facts,
      LocalDateTime now) {
    if (snapshot == null || snapshot.getMedium() == null || snapshot.getDeletedAt() != null) {
      return false;
    }
    var created = createdAt == null ? now : createdAt;
    return snapshot.isCall()
        ? isCallDue(snapshot, created, facts, now)
        : isChatDue(snapshot, created, facts, now);
  }

  private static boolean isCallDue(
      TemporaryAccountSnapshot snapshot,
      LocalDateTime created,
      ConversationFacts facts,
      LocalDateTime now) {
    if (!now.isBefore(created.plusHours(snapshot.getCallMaxLifetimeHours()))) {
      return true;
    }
    if (facts.callRunning() || facts.lastCallEndedAt() == null) {
      return false;
    }
    return !now.isBefore(facts.lastCallEndedAt().plusMinutes(snapshot.getCallDeleteAfterMinutes()));
  }

  private static boolean isChatDue(
      TemporaryAccountSnapshot snapshot,
      LocalDateTime created,
      ConversationFacts facts,
      LocalDateTime now) {
    if (facts.closed()) {
      return true;
    }
    var lastActivity =
        facts.lastActivityAt() != null && facts.lastActivityAt().isAfter(created)
            ? facts.lastActivityAt()
            : created;
    return !now.isBefore(lastActivity.plusDays(snapshot.getChatInactivityDays()));
  }
}
