package de.caritas.cob.userservice.api.conversation.facade;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.ActionCommandMockProvider;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.actions.session.DeactivateSessionActionCommand;
import de.caritas.cob.userservice.api.actions.session.SendFinishedAnonymousConversationEventActionCommand;
import de.caritas.cob.userservice.api.actions.user.DeactivateKeycloakUserActionCommand;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.EndedBy;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Optional;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FinishAnonymousConversationFacadeTest {

  @InjectMocks private FinishAnonymousConversationFacade finishAnonymousConversationFacade;

  @Mock private SessionService sessionService;
  @Mock private de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService claims;

  @Mock
  private de.caritas.cob.userservice.api.service.livechat.LiveChatFinishExecutor finishExecutor;

  @org.junit.jupiter.api.BeforeEach
  void allowUnclaimedGuestDeactivation() {
    org.mockito.Mockito.lenient().when(claims.claimIdentityDeactivation(any())).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(claims.claimNormalFinish(any()))
        .thenReturn(
            de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService.FinishMode
                .CONVERTIBLE_FINISH);
  }

  @Test
  void promotedGuestCanFinishWithoutLosingIdentity() {
    var session = anonymousSession();
    mockAskerOwnsSession(session);
    when(sessionService.getSession(any())).thenReturn(Optional.of(session));
    when(claims.claimNormalFinish(session.getUser().getUserId()))
        .thenReturn(
            de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService.FinishMode
                .KEEP_IDENTITY);
    when(actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(actionCommandMockProvider.getActionContainer(Session.class));
    finishAnonymousConversationFacade.finishConversation(session.getId());
    verify(actionsRegistry, org.mockito.Mockito.never()).buildContainerForType(User.class);
    verify(actionCommandMockProvider.getActionMock(DeactivateSessionActionCommand.class))
        .execute(session);
  }

  @Test
  void legacyFinishDisablesIdentityAndStillFinishesSession() {
    var session = anonymousSession();
    mockAskerOwnsSession(session);
    when(sessionService.getSession(any())).thenReturn(Optional.of(session));
    when(claims.claimNormalFinish(session.getUser().getUserId()))
        .thenReturn(
            de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService.FinishMode
                .LEGACY_DISABLE);
    givenActionContainers();
    finishAnonymousConversationFacade.finishConversation(session.getId());
    verify(actionCommandMockProvider.getActionMock(DeactivateKeycloakUserActionCommand.class))
        .execute(session.getUser());
    verify(actionCommandMockProvider.getActionMock(DeactivateSessionActionCommand.class))
        .execute(session);
    verify(finishExecutor, org.mockito.Mockito.never()).disableAndMarkFinished(any());
  }

  @Test
  void refusedLegacyStyleIdentityDisableStillFinishesSession() {
    var session = anonymousSession();
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setPostcode("00000");
    mockAskerOwnsSession(session);
    when(sessionService.getSession(any())).thenReturn(Optional.of(session));
    when(sessionService.isAnonymousStyleRegistration(session)).thenReturn(true);
    when(claims.claimIdentityDeactivation(session.getUser().getUserId())).thenReturn(false);
    when(actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(actionCommandMockProvider.getActionContainer(Session.class));
    finishAnonymousConversationFacade.finishConversation(session.getId());
    verify(actionsRegistry, org.mockito.Mockito.never()).buildContainerForType(User.class);
    verify(actionCommandMockProvider.getActionMock(DeactivateSessionActionCommand.class))
        .execute(session);
  }

  @Mock private ActionsRegistry actionsRegistry;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  @Mock
  private de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess membersOnlyChatAccess;

  private final ActionCommandMockProvider actionCommandMockProvider =
      new ActionCommandMockProvider();

  private Session anonymousSession() {
    Session session = new EasyRandom().nextObject(Session.class);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    session.getUser().setTemporaryAccount(false);
    return session;
  }

  private void mockAskerOwnsSession(Session session) {
    when(this.authenticatedUser.getUserId()).thenReturn(session.getUser().getUserId());
    when(this.authenticatedUser.isConsultant()).thenReturn(false);
  }

  @Test
  void finishConversation_Should_throwNotFoundException_When_sessionDoesNotExist() {
    when(this.sessionService.getSession(any())).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () -> this.finishAnonymousConversationFacade.finishConversation(1L));
  }

  @Test
  void finishConversation_Should_triggerExpectedActions_When_sessionExists() {
    Session session = anonymousSession();
    mockAskerOwnsSession(session);
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    when(this.actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(Session.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(Session.class));
    this.finishAnonymousConversationFacade.finishConversation(session.getId());

    verify(
            this.actionCommandMockProvider.getActionMock(
                SendFinishedAnonymousConversationEventActionCommand.class),
            times(1))
        .execute(session);
    verify(
            this.actionCommandMockProvider.getActionMock(DeactivateSessionActionCommand.class),
            times(1))
        .execute(session);
    verify(finishExecutor).disableAndMarkFinished(session.getUser().getUserId());
    verify(this.matrixSessionSystemMessageService, times(1))
        .postUserLeftChatMessage(session, EndedBy.ADVICE_SEEKER);
  }

  @Test
  void finishConversation_Should_sayConsultantEndedIt_When_theAssignedConsultantFinishes() {
    Session session = anonymousSession();
    when(this.authenticatedUser.getUserId()).thenReturn(session.getConsultant().getId());
    when(this.authenticatedUser.isConsultant()).thenReturn(true);
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    givenActionContainers();

    this.finishAnonymousConversationFacade.finishConversation(session.getId());

    verify(this.matrixSessionSystemMessageService)
        .postUserLeftChatMessage(session, EndedBy.CONSULTANT);
  }

  @Test
  void finishConversation_Should_notifyTheRoomBeforeDeactivatingTheUser() {
    Session session = anonymousSession();
    mockAskerOwnsSession(session);
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    givenActionContainers();

    this.finishAnonymousConversationFacade.finishConversation(session.getId());

    var order = inOrder(this.matrixSessionSystemMessageService, finishExecutor);
    order
        .verify(this.matrixSessionSystemMessageService)
        .postUserLeftChatMessage(session, EndedBy.ADVICE_SEEKER);
    order.verify(finishExecutor).disableAndMarkFinished(session.getUser().getUserId());
  }

  @Test
  void finishConversation_Should_stillFinish_When_theMatrixNoticeFails() {
    Session session = anonymousSession();
    mockAskerOwnsSession(session);
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    givenActionContainers();
    doThrow(new RuntimeException("matrix down"))
        .when(this.matrixSessionSystemMessageService)
        .postUserLeftChatMessage(session, EndedBy.ADVICE_SEEKER);

    this.finishAnonymousConversationFacade.finishConversation(session.getId());

    verify(this.actionCommandMockProvider.getActionMock(DeactivateSessionActionCommand.class))
        .execute(session);
  }

  private void givenActionContainers() {
    when(this.actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(Session.class));
    org.mockito.Mockito.lenient()
        .when(this.actionsRegistry.buildContainerForType(User.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(User.class));
  }

  @Test
  void finishConversation_Should_triggerExpectedActions_When_registeredLiveChatSessionExists() {
    Session session = anonymousSession();
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    session.setPostcode("00000");
    mockAskerOwnsSession(session);
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    when(this.sessionService.isAnonymousStyleRegistration(session)).thenReturn(true);
    when(this.actionsRegistry.buildContainerForType(Session.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(Session.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(Session.class));
    when(this.actionsRegistry.buildContainerForType(User.class))
        .thenReturn(this.actionCommandMockProvider.getActionContainer(User.class));

    this.finishAnonymousConversationFacade.finishConversation(session.getId());

    verify(
            this.actionCommandMockProvider.getActionMock(DeactivateSessionActionCommand.class),
            times(1))
        .execute(session);
  }

  @Test
  void finishConversation_Should_throwForbiddenException_When_userDoesNotOwnSession() {
    Session session = anonymousSession();
    mockAskerOwnsSession(session);
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    when(this.authenticatedUser.getUserId()).thenReturn("other-user-id");

    assertThrows(
        ForbiddenException.class,
        () -> this.finishAnonymousConversationFacade.finishConversation(session.getId()));
  }

  @Test
  void finishConversation_Should_refuseTheSessionOfAMembersOnlyChat() {
    Session session = anonymousSession();
    when(this.sessionService.getSession(any())).thenReturn(Optional.of(session));
    org.mockito.Mockito.doThrow(new ForbiddenException("members-only"))
        .when(membersOnlyChatAccess)
        .requireOrdinary(org.mockito.ArgumentMatchers.eq(session), any());

    assertThrows(
        ForbiddenException.class,
        () -> this.finishAnonymousConversationFacade.finishConversation(session.getId()));

    org.mockito.Mockito.verifyNoInteractions(actionsRegistry);
  }
}
