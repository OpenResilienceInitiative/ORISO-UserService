package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Performs one anonymous-user deletion batch within a database transaction. */
@Service
@RequiredArgsConstructor
public class AnonymousUserDeletionBatch {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull DeleteUserAccountService deleteUserAccountService;

  @Value("${user.anonymous.deleteworkflow.periodMinutes}")
  private int deletionPeriodMinutes;

  /**
   * Deletes anonymous users whose sessions are done and overdue.
   *
   * <p>The transaction ends when this method returns. Notification must happen in the caller so a
   * mail or tenant lookup failure cannot roll back database deletions after external side effects
   * have already completed.
   */
  @Transactional
  public List<DeletionWorkflowError> deleteOverdueUsers() {
    List<Session> doneSessions = sessionRepository.findByStatus(SessionStatus.DONE);
    LocalDateTime deletionTime = LocalDateTime.now().minusMinutes(deletionPeriodMinutes);

    Set<User> usersWithoutOpenSessions =
        doneSessions.stream()
            .filter(sessionUsersHavingAllSessionsDoneAndOverdue(deletionTime))
            .map(Session::getUser)
            .collect(Collectors.toSet());

    return usersWithoutOpenSessions.stream()
        .map(deleteUserAccountService::performUserDeletion)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  private Predicate<Session> sessionUsersHavingAllSessionsDoneAndOverdue(
      LocalDateTime deletionTime) {
    return session -> {
      Set<Session> userSessions = session.getUser().getSessions();
      return CollectionUtils.isEmpty(userSessions)
          || (allSessionsAreDone(userSessions)
              && allSessionsAreBeforeDeletionTime(deletionTime, userSessions));
    };
  }

  private boolean allSessionsAreDone(Set<Session> sessions) {
    return sessions.stream().map(Session::getStatus).allMatch(SessionStatus.DONE::equals);
  }

  private boolean allSessionsAreBeforeDeletionTime(
      LocalDateTime deletionTime, Set<Session> sessions) {
    return sessions.stream()
        .map(Session::getUpdateDate)
        .allMatch(updateDate -> updateDate.isBefore(deletionTime));
  }
}
