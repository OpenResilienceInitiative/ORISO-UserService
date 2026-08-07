package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Selects the anonymous users whose sessions are done and overdue for deletion. */
@Service
@RequiredArgsConstructor
public class AnonymousUserDeletionCandidates {

  private final @NonNull SessionRepository sessionRepository;

  @Value("${user.anonymous.deleteworkflow.periodMinutes}")
  private int deletionPeriodMinutes;

  /**
   * Returns the ids of the users this workflow may delete.
   *
   * <p>Only sessions registered as {@link RegistrationType#ANONYMOUS} seed the selection. The
   * workflow is configured under {@code user.anonymous.deleteworkflow.*} and must not reach
   * registered accounts; without the filter it also picked up registered system accounts such as
   * the per-tenant {@code group-chat-system-*} users.
   *
   * <p>Ids are returned rather than entities on purpose. Each deletion runs in its own transaction,
   * and an entity loaded here would be detached there, which makes the delete take Hibernate's
   * merge path over an already-initialized session collection — the failure recorded in
   * `documentation/USER_SERVICE_STABILITY.md`.
   */
  @Transactional(readOnly = true)
  public List<String> findOverdueAnonymousUserIds() {
    LocalDateTime deletionTime = LocalDateTime.now().minusMinutes(deletionPeriodMinutes);

    return sessionRepository
        .findByStatusInAndRegistrationType(Set.of(SessionStatus.DONE), RegistrationType.ANONYMOUS)
        .stream()
        .filter(sessionUsersHavingAllSessionsDoneAndOverdue(deletionTime))
        .map(Session::getUser)
        .map(User::getUserId)
        .distinct()
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
