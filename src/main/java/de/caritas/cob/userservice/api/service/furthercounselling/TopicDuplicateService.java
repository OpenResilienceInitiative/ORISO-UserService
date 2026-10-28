package de.caritas.cob.userservice.api.service.furthercounselling;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FE#1876 "Themendopplung": an asker holds two accepted 1:1 conversations on the same selected
 * topic. Same topic means the same {@code mainTopicId}, at any centre.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicDuplicateService {

  /** Older first; equal create dates fall back to the id so the order is total. */
  static final Comparator<Session> AGE =
      Comparator.comparing(
              Session::getCreateDate,
              Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
          .thenComparing(Session::getId, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull FurtherCounsellingSettings settings;
  private final @NonNull MatrixSessionSystemMessageService systemMessages;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull SupervisorAddedEmailNotificationService emailNotifications;

  /** A conversation between one asker and their counsellor: no group, no members-only chat. */
  public static boolean isOneToOne(Session session) {
    var type = session.getConversationType();
    return type == null
        || type == ConversationType.AGENCY_COUNSELLING
        || type == ConversationType.LIVE_CHAT;
  }

  /** The asker's other accepted 1:1 conversations on the same topic. */
  public List<Session> otherActiveSameTopicSessions(Session session) {
    if (session == null || session.getMainTopicId() == null || session.getUser() == null) {
      return List.of();
    }
    return sessionRepository
        .findByUserAndMainTopicId(session.getUser(), session.getMainTopicId())
        .stream()
        .filter(other -> !Objects.equals(other.getId(), session.getId()))
        .filter(other -> other.getStatus() == IN_PROGRESS)
        .filter(TopicDuplicateService::isOneToOne)
        .toList();
  }

  /** Accepted same-topic conversations that started before this one. */
  public List<Session> olderActiveSameTopicSessions(Session session) {
    return otherActiveSameTopicSessions(session).stream()
        .filter(other -> AGE.compare(other, session) < 0)
        .toList();
  }

  /** True when the session is accepted and an older accepted one shares its topic. */
  public boolean isNewerDuplicate(Session session) {
    return session != null
        && session.getStatus() == IN_PROGRESS
        && isOneToOne(session)
        && !olderActiveSameTopicSessions(session).isEmpty();
  }

  /**
   * After an enquiry was accepted: tells every older same-topic conversation, once per (old, new)
   * pair. Runs after the acceptance committed; callers swallow failures.
   */
  public void notifyDuplicateStarted(Long acceptedSessionId) {
    var accepted = sessionRepository.findById(acceptedSessionId).orElse(null);
    if (accepted == null || accepted.getMainTopicId() == null || !isOneToOne(accepted)) {
      return;
    }
    var mode = settings.topicDuplicateMode();
    if (mode != TopicDuplicateMode.INFORM_EXISTING_COUNSELLOR_ONCE) {
      log.warn("Topic duplicate mode {} is not implemented; no notice sent", mode);
      return;
    }
    var body =
        FurtherCounsellingNotices.duplicateStarted(accepted.getMainTopicId(), accepted.getId());
    for (var existing : otherActiveSameTopicSessions(accepted)) {
      // The bell entry is the idempotency marker: only its first writer posts and mails.
      if (!eventNotificationService.createTopicDuplicateStartedNotification(
          existing, accepted.getId())) {
        continue;
      }
      if (!systemMessages.postSystemMessage(existing, body, false)) {
        log.warn("Topic duplicate notice for session {} was not delivered", existing.getId());
      }
      emailNotifications.notifyTopicDuplicateStarted(
          existing.getConsultant(), existing.getId(), TenantContext.getCurrentTenantData());
    }
  }

  /** Posts the "duplicate ended" notice into each given older conversation. */
  public void notifyDuplicateEnded(List<Session> olderSessions, long topicId) {
    var body = FurtherCounsellingNotices.duplicateEnded(topicId);
    for (var existing : olderSessions) {
      try {
        if (!systemMessages.postSystemMessage(existing, body, false)) {
          log.warn("Topic duplicate end notice for session {} not delivered", existing.getId());
        }
      } catch (RuntimeException failure) {
        log.warn(
            "Topic duplicate end notice for session {} failed ({})",
            existing.getId(),
            failure.getClass().getSimpleName());
      }
    }
  }
}
