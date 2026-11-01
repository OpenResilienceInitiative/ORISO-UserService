package de.caritas.cob.userservice.api.service.archive;

import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.furthercounselling.TopicDuplicateService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.ArchiveOrDeleteSessionStatisticsEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Service for archive functionality. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionArchiveService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull SessionArchiveValidator sessionArchiveValidator;
  private final @NonNull AccountManager accountManager;
  private final @NonNull StatisticsService statisticsService;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;
  private final @NonNull TopicDuplicateService topicDuplicateService;

  /**
   * Archive a session.
   *
   * @param sessionId the session id
   */
  public void archiveSession(Long sessionId) {

    Session session = retrieveSession(sessionId);
    // FE#1876: read before the status changes — afterwards the session is no duplicate any more.
    var olderSameTopic = olderSameTopicSessionsOf(session);
    changeSessionStatus(
        sessionId, SessionStatus.IN_ARCHIVE, sessionArchiveValidator::isValidForArchiving);
    fireArchiveOrDeleteSessionEvent(session);
    noticeDuplicateEndedAfterCommit(session, olderSameTopic);
  }

  /**
   * FE#1876 S6: the asker ends the newer of two accepted same-topic conversations. Same archive
   * effect as the counsellor's archive, same notice into the older room.
   *
   * @param sessionId the newer duplicate
   */
  public void endTopicDuplicate(Long sessionId) {
    Session session = retrieveSession(sessionId);
    if (!session.isAdvised(authenticatedUser.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "Ending session %s is not allowed for user with id %s",
              sessionId, authenticatedUser.getUserId()));
    }
    if (session.getStatus() != SessionStatus.IN_PROGRESS
        || !TopicDuplicateService.isOneToOne(session)
        || membersOnlyChatAccess.concerns(session)) {
      throw new ConflictException(
          String.format("Session %s is no accepted 1:1 conversation", sessionId));
    }
    var olderSameTopic = topicDuplicateService.olderActiveSameTopicSessions(session);
    if (olderSameTopic.isEmpty()) {
      throw new ConflictException(
          String.format("Session %s is no newer same-topic duplicate", sessionId));
    }
    executeArchiving(SessionStatus.IN_ARCHIVE, session);
    fireArchiveOrDeleteSessionEvent(session);
    noticeDuplicateEndedAfterCommit(session, olderSameTopic);
  }

  private List<Session> olderSameTopicSessionsOf(Session session) {
    if (session.getStatus() != SessionStatus.IN_PROGRESS
        || session.getMainTopicId() == null
        || !TopicDuplicateService.isOneToOne(session)) {
      return List.of();
    }
    try {
      return topicDuplicateService.olderActiveSameTopicSessions(session);
    } catch (RuntimeException failure) {
      log.warn(
          "Could not check session {} for a same-topic duplicate ({})",
          session.getId(),
          failure.getClass().getSimpleName());
      return List.of();
    }
  }

  /** Shared by both archive paths. A failed notice never undoes the archive. */
  private void noticeDuplicateEndedAfterCommit(Session archived, List<Session> olderSameTopic) {
    if (olderSameTopic.isEmpty() || archived.getMainTopicId() == null) {
      return;
    }
    long topicId = archived.getMainTopicId();
    Runnable notice =
        () -> {
          try {
            topicDuplicateService.notifyDuplicateEnded(olderSameTopic, topicId);
          } catch (RuntimeException failure) {
            log.warn(
                "Topic duplicate end notice for session {} failed ({})",
                archived.getId(),
                failure.getClass().getSimpleName());
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      notice.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            notice.run();
          }
        });
  }

  private void fireArchiveOrDeleteSessionEvent(Session session) {
    try {
      ArchiveOrDeleteSessionStatisticsEvent archiveOrDeleteSessionStatisticsEvent =
          new ArchiveOrDeleteSessionStatisticsEvent(
              session.getUser(), session.getId(), LocalDateTime.now());
      statisticsService.fireEvent(archiveOrDeleteSessionStatisticsEvent);
    } catch (Exception e) {
      log.error("Could not create session archive statistics event", e);
    }
  }

  /**
   * Dearchive a session.
   *
   * @param sessionId the session id
   */
  public void dearchiveSession(Long sessionId) {
    changeSessionStatus(
        sessionId, SessionStatus.IN_PROGRESS, sessionArchiveValidator::isValidForDearchiving);
  }

  private void changeSessionStatus(
      Long sessionId, SessionStatus sessionStatusTo, Consumer<Session> sessionValidateMethod) {

    Session session = retrieveSession(sessionId);
    checkPermission(session);
    // The technical session of a members-only chat lives and ends with the chat: archiving it, or
    // bringing it back, under a chat that stays open is refused. Whoever is no participant was
    // already refused with 403 above.
    if (membersOnlyChatAccess.concerns(session)) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.MEMBERS_ONLY_CHAT_STOP_REQUIRED, HttpStatus.CONFLICT);
    }
    sessionValidateMethod.accept(session);
    executeArchiving(sessionStatusTo, session);
  }

  public void checkPermission(Session session) {
    if (membersOnlyChatAccess.excludes(session, authenticatedUser.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "Archive/reactivate session %s is not allowed for user with id %s",
              session.getId(), authenticatedUser.getUserId()));
    }
    if (!hasConsultantPermission(session) && !session.isAdvised(authenticatedUser.getUserId())) {
      var template = "Archive/reactivate session %s is not allowed for user with id %s";
      var message = String.format(template, session.getId(), authenticatedUser.getUserId());

      throw new ForbiddenException(message);
    }
  }

  private boolean hasConsultantPermission(Session session) {
    var userId = authenticatedUser.getUserId();

    return session.isAdvisedBy(userId) || accountManager.isTeamAdvisedBy(session.getId(), userId);
  }

  private Session retrieveSession(Long sessionId) {
    return sessionRepository
        .findById(sessionId)
        .orElseThrow(() -> new NotFoundException("Session with id %s not found.", sessionId));
  }

  private void executeArchiving(SessionStatus sessionStatusTo, Session session) {
    try {
      session.setStatus(sessionStatusTo);
      sessionRepository.save(session);
    } catch (InternalServerErrorException ex) {
      throw new InternalServerErrorException(
          String.format(
              "Could not archive/dearchive session %s for user %s",
              session.getId(), authenticatedUser.getUserId()),
          ex);
    }
  }
}
