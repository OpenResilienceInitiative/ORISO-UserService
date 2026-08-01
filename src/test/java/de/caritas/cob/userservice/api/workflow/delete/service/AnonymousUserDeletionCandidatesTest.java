package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnonymousUserDeletionCandidatesTest {

  private static final int DELETION_PERIOD_MINUTES = 1200;
  private static final String USER_ID = "user-id";

  @InjectMocks private AnonymousUserDeletionCandidates deletionCandidates;

  @Mock private SessionRepository sessionRepository;

  @BeforeEach
  public void setUp() {
    ReflectionTestUtils.setField(
        deletionCandidates, "deletionPeriodMinutes", DELETION_PERIOD_MINUTES);
  }

  @Test
  void findOverdueAnonymousUserIds_Should_onlyConsiderAnonymousSessions() {
    when(sessionRepository.findByStatusInAndRegistrationType(any(), any())).thenReturn(List.of());

    deletionCandidates.findOverdueAnonymousUserIds();

    verify(sessionRepository)
        .findByStatusInAndRegistrationType(Set.of(SessionStatus.DONE), RegistrationType.ANONYMOUS);
  }

  @Test
  void findOverdueAnonymousUserIds_Should_returnNothing_When_noSessionIsAvailable() {
    when(sessionRepository.findByStatusInAndRegistrationType(any(), any())).thenReturn(List.of());

    assertThat(deletionCandidates.findOverdueAnonymousUserIds()).isEmpty();
  }

  @Test
  void findOverdueAnonymousUserIds_Should_returnNothing_When_noSessionIsDone() {
    whenRepositoryReturnsUserSessionsWithStatus(getAnyStatusWhichIsNotDone());

    assertThat(deletionCandidates.findOverdueAnonymousUserIds()).isEmpty();
  }

  @Test
  void findOverdueAnonymousUserIds_Should_returnNothing_When_notAllSessionsAreDone() {
    whenRepositoryReturnsUserSessionsWithStatus(SessionStatus.IN_PROGRESS, SessionStatus.DONE);

    assertThat(deletionCandidates.findOverdueAnonymousUserIds()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("createUpdateDatesWithinDeletionPeriod")
  void findOverdueAnonymousUserIds_Should_returnNothing_When_sessionsAreDoneWithinDeletionPeriod(
      LocalDateTime updateDate) {
    whenRepositoryReturnsOneUserSessionUpdatedAt(updateDate);

    assertThat(deletionCandidates.findOverdueAnonymousUserIds()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("createOverdueUpdateDates")
  void findOverdueAnonymousUserIds_Should_returnUserId_When_sessionsAreDoneAndOverdue(
      LocalDateTime overdueUpdateDate) {
    whenRepositoryReturnsOneUserSessionUpdatedAt(overdueUpdateDate);

    assertThat(deletionCandidates.findOverdueAnonymousUserIds()).containsExactly(USER_ID);
  }

  @Test
  void findOverdueAnonymousUserIds_Should_returnEachUserOnce_When_userHasSeveralDoneSessions() {
    whenRepositoryReturnsUserSessionsWithStatus(SessionStatus.DONE, SessionStatus.DONE);

    assertThat(deletionCandidates.findOverdueAnonymousUserIds()).containsExactly(USER_ID);
  }

  private SessionStatus[] getAnyStatusWhichIsNotDone() {
    List<SessionStatus> anyStatusNotDone = new ArrayList<>(List.of(SessionStatus.values()));
    anyStatusNotDone.remove(SessionStatus.DONE);
    return anyStatusNotDone.toArray(SessionStatus[]::new);
  }

  private void whenRepositoryReturnsUserSessionsWithStatus(SessionStatus... sessionStatus) {
    User user = anonymousUser();
    Set<Session> userSessions =
        Stream.of(sessionStatus)
            .map(createSessionForUserWithUpdateDate(user, overdueUpdateDate()))
            .collect(Collectors.toSet());
    user.setSessions(userSessions);

    when(sessionRepository.findByStatusInAndRegistrationType(any(), any()))
        .thenReturn(new ArrayList<>(userSessions));
  }

  private void whenRepositoryReturnsOneUserSessionUpdatedAt(LocalDateTime updateDate) {
    User user = anonymousUser();
    Set<Session> userSessions = Set.of(createSessionForUser(user, updateDate, SessionStatus.DONE));
    user.setSessions(userSessions);

    when(sessionRepository.findByStatusInAndRegistrationType(any(), any()))
        .thenReturn(new ArrayList<>(userSessions));
  }

  private User anonymousUser() {
    User user = new User();
    user.setUserId(USER_ID);
    return user;
  }

  private LocalDateTime overdueUpdateDate() {
    return LocalDateTime.now().minusMinutes(DELETION_PERIOD_MINUTES).minusSeconds(1);
  }

  private Function<SessionStatus, Session> createSessionForUserWithUpdateDate(
      User user, LocalDateTime sessionUpdateDate) {
    return (sessionStatus) -> createSessionForUser(user, sessionUpdateDate, sessionStatus);
  }

  private Session createSessionForUser(
      User user, LocalDateTime updateDate, SessionStatus sessionStatus) {
    Session session = new Session();
    session.setId((long) sessionStatus.getValue());
    session.setUpdateDate(updateDate);
    session.setStatus(sessionStatus);
    session.setUser(user);
    return session;
  }

  private static List<LocalDateTime> createUpdateDatesWithinDeletionPeriod() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime oneSecondWithinDeletionPeriod =
        now.minusMinutes(DELETION_PERIOD_MINUTES).plusSeconds(1);
    LocalDateTime timeInTheFuture = now.plusSeconds(20);

    return List.of(now, oneSecondWithinDeletionPeriod, timeInTheFuture);
  }

  private static List<LocalDateTime> createOverdueUpdateDates() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime oneDeletionPeriodAgo = now.minusMinutes(DELETION_PERIOD_MINUTES).minusSeconds(1);
    LocalDateTime timeLongInThePast = oneDeletionPeriodAgo.minusMinutes(10);

    return List.of(oneDeletionPeriodAgo, timeLongInThePast);
  }
}
