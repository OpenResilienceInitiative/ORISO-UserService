package de.caritas.cob.userservice.api.workflow.deactivate.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.ActionCommandMockProvider;
import de.caritas.cob.userservice.api.actions.registry.ActionContainer;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.session.DeactivateSessionActionCommand;
import de.caritas.cob.userservice.api.actions.session.PostConversationFinishedAliasMessageActionCommand;
import de.caritas.cob.userservice.api.actions.session.SendFinishedAnonymousConversationEventActionCommand;
import de.caritas.cob.userservice.api.actions.session.SetRocketChatRoomReadOnlyActionCommand;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DeactivateAnonymousUserServiceTest {

  private static final int DEACTIVATE_PERIOD_MINUTES = 360;

  @InjectMocks private DeactivateAnonymousUserService deactivateAnonymousUserService;

  @Mock private SessionRepository sessionRepository;

  @Mock private ActionsRegistry actionsRegistry;

  private final ActionCommandMockProvider commandMockProvider = new ActionCommandMockProvider();

  @BeforeEach
  public void setUp() {
    ReflectionTestUtils.setField(
        deactivateAnonymousUserService, "deactivatePeriodMinutes", DEACTIVATE_PERIOD_MINUTES);
    ensureSessionActionMocks();
  }

  @SuppressWarnings("unchecked")
  private void ensureSessionActionMocks() {
    Stream.of(
            DeactivateSessionActionCommand.class,
            PostConversationFinishedAliasMessageActionCommand.class,
            SetRocketChatRoomReadOnlyActionCommand.class,
            SendFinishedAnonymousConversationEventActionCommand.class)
        .forEach(
            actionClass -> {
              if (this.commandMockProvider.getActionMock(actionClass) == null) {
                this.commandMockProvider.setCustomClassForAction(actionClass, mock(actionClass));
              }
            });
  }

  @Test
  void deactivateStaleAnonymousUsers_Should_notUseServices_When_noSessionIsAvailable() {
    this.deactivateAnonymousUserService.deactivateStaleAnonymousUsers();

    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(User.class);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(Session.class);
  }

  @Test
  void deactivateStaleAnonymousUsers_Should_notPerformAnyDeactivation_When_noSessionIsInProgress() {
    whenSessionRepositoryFindByStatus_ThenReturnUserSessionsWithStatus(
        getAnyStatusWhichIsNotInProgress());
    var deactivateUserAction = mock(DeactivateKeycloakUserActionCommand.class);

    when(this.actionsRegistry.buildContainerForType(User.class))
        .thenReturn(new ActionContainer<>(Set.of(deactivateUserAction)));
    when(this.actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(this.commandMockProvider.getActionContainer(Session.class));

    this.deactivateAnonymousUserService.deactivateStaleAnonymousUsers();

    verify(this.sessionRepository, times(1))
        .findLiveChatSessionsByStatusIn(
            Set.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS), RegistrationType.ANONYMOUS);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(User.class);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(Session.class);
    verifyNoMoreInteractions(deactivateUserAction);
    verifyNoSessionDeactivationActionsExecuted();
  }

  private SessionStatus[] getAnyStatusWhichIsNotInProgress() {
    List<SessionStatus> anyStatusNotInProgress = new ArrayList<>(List.of(SessionStatus.values()));
    anyStatusNotInProgress.remove(SessionStatus.IN_PROGRESS);
    return anyStatusNotInProgress.toArray(SessionStatus[]::new);
  }

  private void whenSessionRepositoryFindByStatus_ThenReturnUserSessionsWithStatus(
      SessionStatus... sessionStatus) {
    var user = new User();
    var userSessions =
        Stream.of(sessionStatus)
            .map(createSessionForUserWithUpdateDateNow(user))
            .collect(Collectors.toSet());
    user.setSessions(userSessions);

    when(this.sessionRepository.findLiveChatSessionsByStatusIn(any(), any()))
        .thenReturn(new ArrayList<>(userSessions));
  }

  private Function<SessionStatus, Session> createSessionForUserWithUpdateDateNow(User user) {
    return createSessionForUserWithUpdateDate(user, LocalDateTime.now());
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
    session.setRegistrationType(RegistrationType.ANONYMOUS);
    return session;
  }

  @ParameterizedTest
  @EnumSource(WithinDeactivationPeriodScenario.class)
  void
      deactivateStaleAnonymousUsers_Should_notPerformAnyDeactivation_When_sessionsAreInProgressWithinDeactivatePeriod(
          WithinDeactivationPeriodScenario scenario) {
    var updateDate = updateDateWithinDeactivationPeriod(scenario);
    var user = createUserWithSingleSession(updateDate);
    when(this.sessionRepository.findLiveChatSessionsByStatusIn(any(), any()))
        .thenReturn(new ArrayList<>(user.getSessions()));
    var deactivateUserAction = mock(DeactivateKeycloakUserActionCommand.class);
    when(this.actionsRegistry.buildContainerForType(User.class))
        .thenReturn(new ActionContainer<>(Set.of(deactivateUserAction)));
    when(this.actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(this.commandMockProvider.getActionContainer(Session.class));

    this.deactivateAnonymousUserService.deactivateStaleAnonymousUsers();

    verify(this.sessionRepository, times(1))
        .findLiveChatSessionsByStatusIn(
            Set.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS), RegistrationType.ANONYMOUS);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(User.class);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(Session.class);
    verifyNoMoreInteractions(deactivateUserAction);
    verifyNoSessionDeactivationActionsExecuted();
  }

  private enum WithinDeactivationPeriodScenario {
    NOW,
    JUST_INSIDE_BOUNDARY,
    IN_THE_FUTURE
  }

  private static LocalDateTime updateDateWithinDeactivationPeriod(
      WithinDeactivationPeriodScenario scenario) {
    LocalDateTime now = LocalDateTime.now();
    return switch (scenario) {
      case NOW -> now;
      case JUST_INSIDE_BOUNDARY -> now.minusMinutes(DEACTIVATE_PERIOD_MINUTES).plusSeconds(10);
      case IN_THE_FUTURE -> now.plusSeconds(20);
    };
  }

  private void verifyNoSessionDeactivationActionsExecuted() {
    verify(this.commandMockProvider.getActionMock(DeactivateSessionActionCommand.class), never())
        .execute(any(Session.class));
    verify(
            this.commandMockProvider.getActionMock(
                PostConversationFinishedAliasMessageActionCommand.class),
            never())
        .execute(any(Session.class));
    verify(
            this.commandMockProvider.getActionMock(SetRocketChatRoomReadOnlyActionCommand.class),
            never())
        .execute(any(Session.class));
    verify(
            this.commandMockProvider.getActionMock(
                SendFinishedAnonymousConversationEventActionCommand.class),
            never())
        .execute(any(Session.class));
  }

  private User createUserWithSingleSession(LocalDateTime updateDate) {
    var user = new User();
    user.setUserId("user id");
    var userSessions = Set.of(createSessionForUser(user, updateDate, SessionStatus.IN_PROGRESS));
    user.setSessions(userSessions);
    return user;
  }

  @ParameterizedTest
  @EnumSource(OverdueDeactivationScenario.class)
  void
      deactivateStaleAnonymousUsers_Should_callUserAndSessionDeactivateActions_When_userSessionsAreInProgressForTooLong(
          OverdueDeactivationScenario scenario) {
    var overdueUpdateDate = overdueUpdateDate(scenario);
    var user = createUserWithSingleSession(overdueUpdateDate);

    when(this.sessionRepository.findLiveChatSessionsByStatusIn(any(), any()))
        .thenReturn(new ArrayList<>(user.getSessions()));

    var deactivateUserAction = mock(DeactivateKeycloakUserActionCommand.class);
    when(this.actionsRegistry.buildContainerForType(User.class))
        .thenReturn(new ActionContainer<>(Set.of(deactivateUserAction)));
    when(this.actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(this.commandMockProvider.getActionContainer(Session.class));

    this.deactivateAnonymousUserService.deactivateStaleAnonymousUsers();

    verify(this.sessionRepository, times(1))
        .findLiveChatSessionsByStatusIn(
            Set.of(SessionStatus.NEW, SessionStatus.IN_PROGRESS), RegistrationType.ANONYMOUS);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(User.class);
    verify(this.actionsRegistry, atLeastOnce()).buildContainerForType(Session.class);
    verify(deactivateUserAction, times(1)).execute(user);
    user.getSessions()
        .forEach(
            session -> {
              verify(
                      this.commandMockProvider.getActionMock(DeactivateSessionActionCommand.class),
                      times(1))
                  .execute(session);
              verify(
                      this.commandMockProvider.getActionMock(
                          SetRocketChatRoomReadOnlyActionCommand.class),
                      times(1))
                  .execute(session);
              verify(
                      this.commandMockProvider.getActionMock(
                          SendFinishedAnonymousConversationEventActionCommand.class),
                      times(1))
                  .execute(session);
              verify(
                      this.commandMockProvider.getActionMock(
                          PostConversationFinishedAliasMessageActionCommand.class),
                      times(1))
                  .execute(session);
            });
  }

  private enum OverdueDeactivationScenario {
    JUST_PAST_BOUNDARY,
    LONG_IN_THE_PAST
  }

  private static LocalDateTime overdueUpdateDate(OverdueDeactivationScenario scenario) {
    LocalDateTime deactivationCutoff = LocalDateTime.now().minusMinutes(DEACTIVATE_PERIOD_MINUTES);
    return switch (scenario) {
      case JUST_PAST_BOUNDARY -> deactivationCutoff.minusSeconds(1);
      case LONG_IN_THE_PAST -> deactivationCutoff.minusMinutes(10);
    };
  }
}
