package de.caritas.cob.userservice.api.service.furthercounselling;

import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.notification.SupervisorAddedEmailNotificationService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
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

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull FurtherCounsellingSettings settings;
  private final @NonNull MatrixSessionSystemMessageService systemMessages;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull SupervisorAddedEmailNotificationService emailNotifications;

  /** A conversation between one asker and their counsellor: no group, no members-only chat. */
  public static boolean isOneToOne(Session session) {
    return TopicDuplicateRule.isOneToOne(session.getConversationType());
  }

  static TopicDuplicateRule.Facts facts(Session session) {
    return new TopicDuplicateRule.Facts(
        session.getId(),
        session.getMainTopicId(),
        session.getStatus() == IN_PROGRESS,
        session.getConversationType(),
        session.getCreateDate());
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
        .filter(other -> TopicDuplicateRule.counts(facts(other)))
        .toList();
  }

  /** Accepted same-topic conversations that started before this one. */
  public List<Session> olderActiveSameTopicSessions(Session session) {
    return otherActiveSameTopicSessions(session).stream()
        .filter(other -> TopicDuplicateRule.isOlderSameTopic(facts(other), facts(session)))
        .toList();
  }

  /** True when the session is accepted and an older accepted one shares its topic. */
  public boolean isNewerDuplicate(Session session) {
    return session != null
        && TopicDuplicateRule.counts(facts(session))
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
    var body = FurtherCounsellingNotices.duplicateStarted(accepted.getMainTopicId());
    for (var existing : otherActiveSameTopicSessions(accepted)) {
      try {
        notifyOnce(existing, accepted.getId(), body);
      } catch (RuntimeException failure) {
        log.warn(
            "Topic duplicate notice for session {} failed ({})",
            existing.getId(),
            failure.getClass().getSimpleName());
      }
    }
  }

  /**
   * The bell entry is the once-per-pair marker. It is claimed first, so two runs cannot both post,
   * and released again when the post fails, so a later run can still deliver.
   */
  private void notifyOnce(Session existing, Long acceptedId, String body) {
    if (!eventNotificationService.createTopicDuplicateStartedNotification(existing, acceptedId)) {
      return;
    }
    boolean posted;
    try {
      posted = systemMessages.postSystemMessage(existing, body, false);
    } catch (RuntimeException failure) {
      eventNotificationService.removeTopicDuplicateStartedNotification(existing, acceptedId);
      throw failure;
    }
    if (!posted) {
      eventNotificationService.removeTopicDuplicateStartedNotification(existing, acceptedId);
      log.warn("Topic duplicate notice for session {} was not delivered", existing.getId());
      return;
    }
    emailNotifications.notifyTopicDuplicateStarted(
        existing.getConsultant(), existing.getId(), TenantContext.getCurrentTenantData());
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
