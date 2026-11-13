package de.caritas.cob.userservice.api.service.archive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.registry.ActionContainer;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.statistics.StatisticsService;
import de.caritas.cob.userservice.api.service.statistics.event.ArchiveOrDeleteSessionStatisticsEvent;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteSingleRoomAndSessionAction;
import de.caritas.cob.userservice.api.workflow.delete.model.SessionDeletionWorkflowDTO;
import java.util.Optional;
import java.util.Set;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionDeleteServiceTest {

  private final EasyRandom easyRandom = new EasyRandom();

  @InjectMocks SessionDeleteService sessionDeleteService;

  @Mock ActionsRegistry actionsRegistry;
  @Mock de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService claims;

  @org.junit.jupiter.api.BeforeEach
  void allowUnclaimedIdentityDeactivation() {
    org.mockito.Mockito.lenient().when(claims.claimIdentityDeactivation(any())).thenReturn(true);
  }

  @Test
  void deletingLastSessionKeepsPromotedIdentityActive() {
    var sessionId = givenAPresentSession(true);
    var session = sessionService.getSession(sessionId).orElseThrow();
    when(claims.claimIdentityDeactivation(session.getUser().getUserId())).thenReturn(false);
    var delete = givenActionRegistryDeletesSession();
    sessionDeleteService.deleteSession(sessionId);
    verify(delete).executeActions(any(SessionDeletionWorkflowDTO.class));
    verify(actionsRegistry, org.mockito.Mockito.never()).buildContainerForType(User.class);
  }

  @Mock StatisticsService statisticsService;

  @Mock SessionService sessionService;

  @Mock MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  @Mock de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  @Mock de.caritas.cob.userservice.api.helper.AuthenticatedUser authenticatedUser;

  @Mock
  de.caritas.cob.userservice.api.service.session.DeletedAskerSessionService
      deletedAskerSessionService;

  @Test
  void deleteSessionAndInactiveUser_Should_ReturnOK_When_SessionIdIsKnown() {
    var sessionId = givenAPresentSession(false);
    var actionContainer = givenActionRegistryDeletesSession();

    sessionDeleteService.deleteSession(sessionId);

    verify(sessionService).getSession(sessionId);
    verify(actionContainer).executeActions(any(SessionDeletionWorkflowDTO.class));
    verify(actionsRegistry).buildContainerForType(SessionDeletionWorkflowDTO.class);
    verify(statisticsService).fireEvent(any(ArchiveOrDeleteSessionStatisticsEvent.class));
    verifyNoMoreInteractions(actionsRegistry);
  }

  @Test
  void deleteSessionAndInactiveUser_Should_DeactivateKeycloakUser_When_OnlySession()
      throws Exception {
    var sessionId = givenAPresentSession(true);
    var actionContainerDelete = givenActionRegistryDeletesSession();
    var actionContainerDeactivate = givenActionRegistryDeactivatesKeycloakUser();

    sessionDeleteService.deleteSession(sessionId);

    verify(sessionService).getSession(sessionId);
    verify(actionContainerDelete).executeActions(any(SessionDeletionWorkflowDTO.class));
    verify(actionContainerDeactivate).executeActions(any(User.class));
    verify(actionsRegistry).buildContainerForType(SessionDeletionWorkflowDTO.class);
    verify(actionsRegistry).buildContainerForType(User.class);
    verify(statisticsService).fireEvent(any(ArchiveOrDeleteSessionStatisticsEvent.class));
  }

  @Test
  void deleteSession_Should_refuseAConsultantWhoIsNoParticipantOfAMembersOnlyChat() {
    var sessionId = givenAPresentSession(false);
    when(authenticatedUser.getUserId()).thenReturn("outsider");
    org.mockito.Mockito.doThrow(
            new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException("no"))
        .when(membersOnlyChatAccess)
        .requireAccess(any(Session.class), org.mockito.ArgumentMatchers.eq("outsider"));

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> sessionDeleteService.deleteSession(sessionId));

    org.mockito.Mockito.verifyNoInteractions(actionsRegistry);
  }

  @Test
  void deleteSession_Should_answerAParticipantWithConflictAndTheReason() {
    var sessionId = givenAPresentSession(false);
    when(membersOnlyChatAccess.concerns(any(Session.class))).thenReturn(true);

    var refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses
                .CustomValidationHttpStatusException.class,
            () -> sessionDeleteService.deleteSession(sessionId));

    org.junit.jupiter.api.Assertions.assertEquals(
        org.springframework.http.HttpStatus.CONFLICT, refused.getHttpStatus());
    org.junit.jupiter.api.Assertions.assertEquals(
        "MEMBERS_ONLY_CHAT_STOP_REQUIRED", refused.getCustomHttpHeaders().getFirst("X-Reason"));
    org.mockito.Mockito.verifyNoInteractions(actionsRegistry);
  }

  @Test
  void deleteSession_Should_sayConsultantEndedIt_When_aConsultantDeletes() {
    var sessionId = givenAPresentSession(false);
    givenActionRegistryDeletesSession();
    when(authenticatedUser.isConsultant()).thenReturn(true);

    sessionDeleteService.deleteSession(sessionId);

    verify(matrixSessionSystemMessageService)
        .postUserLeftChatMessage(
            any(Session.class),
            org.mockito.ArgumentMatchers.eq(MatrixSessionSystemMessageService.EndedBy.CONSULTANT));
  }

  @Test
  void deleteSession_Should_sayAdviceSeekerEndedIt_When_theAskerDeletes() {
    var sessionId = givenAPresentSession(false);
    givenActionRegistryDeletesSession();
    givenTheAdviceSeekerDeletes(sessionService.getSession(sessionId).orElseThrow());

    sessionDeleteService.deleteSession(sessionId);

    verify(matrixSessionSystemMessageService)
        .postUserLeftChatMessage(
            any(Session.class),
            org.mockito.ArgumentMatchers.eq(
                MatrixSessionSystemMessageService.EndedBy.ADVICE_SEEKER));
  }

  @Test
  void deleteSession_Should_leaveThePlaceholder_When_theAskerDeletes() {
    var sessionId = givenAPresentSession(false);
    givenActionRegistryDeletesSession();
    givenTheAdviceSeekerDeletes(sessionService.getSession(sessionId).orElseThrow());

    sessionDeleteService.deleteSession(sessionId);

    verify(deletedAskerSessionService)
        .recordDeletionAfterCommit(
            de.caritas.cob.userservice.api.service.session.DeletedAskerSessionService.Placeholder
                .of(sessionService.getSession(sessionId).orElseThrow())
                .orElseThrow());
  }

  @Test
  void deleteSession_Should_leaveThePlaceholder_When_aConsultantDeletes() {
    var sessionId = givenAPresentSession(false);
    givenActionRegistryDeletesSession();
    when(authenticatedUser.isConsultant()).thenReturn(true);

    sessionDeleteService.deleteSession(sessionId);

    verify(deletedAskerSessionService)
        .recordDeletionAfterCommit(
            de.caritas.cob.userservice.api.service.session.DeletedAskerSessionService.Placeholder
                .of(sessionService.getSession(sessionId).orElseThrow())
                .orElseThrow());
  }

  @Test
  void deleteSession_Should_stillDelete_When_recordingThePlaceholderFails() {
    var sessionId = givenAPresentSession(false);
    givenActionRegistryDeletesSession();
    org.mockito.Mockito.doThrow(new RuntimeException("placeholder failed"))
        .when(deletedAskerSessionService)
        .recordDeletionAfterCommit(any());

    sessionDeleteService.deleteSession(sessionId);

    verify(statisticsService).fireEvent(any(ArchiveOrDeleteSessionStatisticsEvent.class));
  }

  @Test
  void deleteSession_doesNotRecordPlaceholderWithoutConsultant() {
    var sessionId = givenAPresentSession(false);
    var session = sessionService.getSession(sessionId).orElseThrow();
    session.setConsultant(null);
    givenTheAdviceSeekerDeletes(session);
    givenActionRegistryDeletesSession();
    sessionDeleteService.deleteSession(sessionId);
    org.mockito.Mockito.verifyNoInteractions(deletedAskerSessionService);
  }

  // ORISO-Admin#1518: a counsellor's delete removed a registered advice seeker's whole
  // conversation and could deactivate their account. Counsellors archive such chats instead.
  @Test
  void deleteSession_Should_refuseTheAssignedConsultant_When_theAdviceSeekerIsRegistered() {
    var sessionId = givenAPresentSession(true);
    var session = sessionService.getSession(sessionId).orElseThrow();
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setConversationType(
        de.caritas.cob.userservice.api.model.ConversationType.AGENCY_COUNSELLING);
    session.setPostcode("12345");
    session.getUser().setUsername("registered-asker");

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> sessionDeleteService.deleteSession(sessionId));

    org.mockito.Mockito.verifyNoInteractions(
        actionsRegistry, matrixSessionSystemMessageService, deletedAskerSessionService);
  }

  @Test
  void deleteSession_Should_refuseAConsultantWhoIsNotAssigned_When_theChatIsAnonymous() {
    var sessionId = givenAPresentSession(true);
    when(authenticatedUser.getUserId()).thenReturn("another-consultant");

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> sessionDeleteService.deleteSession(sessionId));

    org.mockito.Mockito.verifyNoInteractions(
        actionsRegistry, matrixSessionSystemMessageService, deletedAskerSessionService);
  }

  @Test
  void deleteSession_Should_letTheAssignedConsultantDelete_When_theChatIsAnonymousStyle() {
    var sessionId = givenAPresentSession(false);
    var session = sessionService.getSession(sessionId).orElseThrow();
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setConversationType(null);
    session.getUser().setUsername("Anonymous-4711");
    var delete = givenActionRegistryDeletesSession();

    sessionDeleteService.deleteSession(sessionId);

    verify(delete).executeActions(any(SessionDeletionWorkflowDTO.class));
  }

  @Test
  void deleteSession_Should_refuseAnAdviceSeekerWhoDoesNotOwnTheSession() {
    var sessionId = givenAPresentSession(false);
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.getUserId()).thenReturn("someone-else");

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> sessionDeleteService.deleteSession(sessionId));

    org.mockito.Mockito.verifyNoInteractions(actionsRegistry);
  }

  // A group chat's backing session carries the dummy postcode 00000, is REGISTERED and has its
  // creator assigned: the anonymous postcode rule alone would let the creator delete the group.
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.ConversationType.class,
      names = {"INTERNAL_GROUP", "SELF_HELP"})
  void deleteSession_Should_refuseTheCreator_When_theSessionBacksAGroupChat(
      de.caritas.cob.userservice.api.model.ConversationType groupType) {
    var sessionId = givenAPresentSession(true);
    var session = sessionService.getSession(sessionId).orElseThrow();
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setConversationType(groupType);
    session.setPostcode("00000");

    org.junit.jupiter.api.Assertions.assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> sessionDeleteService.deleteSession(sessionId));

    org.mockito.Mockito.verifyNoInteractions(
        actionsRegistry, matrixSessionSystemMessageService, deletedAskerSessionService);
  }

  @Test
  void deleteSession_Should_letTheAssignedConsultantDelete_When_aLiveChatIsNoLongerAnonymous() {
    var sessionId = givenAPresentSession(false);
    var session = sessionService.getSession(sessionId).orElseThrow();
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setPostcode("12345");
    session.getUser().setUsername("converted-asker");
    var delete = givenActionRegistryDeletesSession();

    sessionDeleteService.deleteSession(sessionId);

    verify(delete).executeActions(any(SessionDeletionWorkflowDTO.class));
  }

  /**
   * An anonymous live-chat session, deleted by its assigned consultant: the one case the
   * counsellor-facing endpoint still serves.
   */
  private long givenAPresentSession(boolean isOnlySession) {
    var sessionId = easyRandom.nextLong();
    var session = easyRandom.nextObject(Session.class);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    session.setConversationType(de.caritas.cob.userservice.api.model.ConversationType.LIVE_CHAT);
    if (isOnlySession) {
      session.getUser().setSessions(Set.of(session));
    }

    when(sessionService.getSession(sessionId)).thenReturn(Optional.of(session));
    org.mockito.Mockito.lenient().when(authenticatedUser.isConsultant()).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(authenticatedUser.getUserId())
        .thenReturn(session.getConsultant().getId());

    return sessionId;
  }

  private void givenTheAdviceSeekerDeletes(Session session) {
    org.mockito.Mockito.lenient().when(authenticatedUser.isConsultant()).thenReturn(false);
    org.mockito.Mockito.lenient()
        .when(authenticatedUser.getUserId())
        .thenReturn(session.getUser().getUserId());
  }

  private ActionContainer<SessionDeletionWorkflowDTO> givenActionRegistryDeletesSession() {
    @SuppressWarnings("unchecked")
    var actionContainer = (ActionContainer<SessionDeletionWorkflowDTO>) mock(ActionContainer.class);
    when(actionContainer.addActionToExecute(DeleteSingleRoomAndSessionAction.class))
        .thenReturn(actionContainer);
    when(actionsRegistry.buildContainerForType(SessionDeletionWorkflowDTO.class))
        .thenReturn(actionContainer);

    return actionContainer;
  }

  private ActionContainer<User> givenActionRegistryDeactivatesKeycloakUser() {
    @SuppressWarnings("unchecked")
    var actionContainer = (ActionContainer<User>) mock(ActionContainer.class);
    when(actionContainer.addActionToExecute(DeactivateKeycloakUserActionCommand.class))
        .thenReturn(actionContainer);
    when(actionsRegistry.buildContainerForType(User.class)).thenReturn(actionContainer);

    return actionContainer;
  }
}
