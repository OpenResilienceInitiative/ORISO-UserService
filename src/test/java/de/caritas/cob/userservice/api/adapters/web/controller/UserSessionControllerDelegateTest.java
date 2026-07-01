package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.container.SessionListQueryParameter;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.assignsession.AssignSessionFacade;
import de.caritas.cob.userservice.api.facade.sessionlist.SessionListFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserSessionControllerDelegateTest {

  @Mock private UserAccountService userAccountProvider;
  @Mock private SessionService sessionService;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private SessionListFacade sessionListFacade;
  @Mock private AssignSessionFacade assignSessionFacade;
  @Mock private ConsultantDataFacade consultantDataFacade;
  @Mock private SessionArchiveService sessionArchiveService;
  @Mock private AccountManaging accountManager;
  @Mock private Messaging messenger;
  @Mock private ConsultantDtoMapper consultantDtoMapper;
  @Mock private UserDtoMapper userDtoMapper;
  @Mock private ConsultantService consultantService;

  @InjectMocks private UserSessionControllerDelegate delegate;

  @Test
  void getSessionsForAuthenticatedUserShouldReturnOkAndUseFallbackRocketChatCredentials() {
    var responseDto =
        new UserSessionListResponseDTO().sessions(List.of(new UserSessionResponseDTO()));
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(userWithoutRocketChatId());
    when(sessionListFacade.retrieveSortedSessionsForAuthenticatedUser(eq("user-id"), any()))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForAuthenticatedUser(null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);

    var credentialsCaptor = ArgumentCaptor.forClass(RocketChatCredentials.class);
    verify(sessionListFacade)
        .retrieveSortedSessionsForAuthenticatedUser(eq("user-id"), credentialsCaptor.capture());
    assertThat(credentialsCaptor.getValue().getRocketChatUserId()).isEqualTo("dummy-rc-user");
    assertThat(credentialsCaptor.getValue().getRocketChatToken()).isEqualTo("dummy-rc-token");
  }

  @Test
  void getSessionsForAuthenticatedUserShouldReturnNoContentWhenNoSessionsExist() {
    var responseDto = new UserSessionListResponseDTO().sessions(List.of());
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(userWithoutRocketChatId());
    when(sessionListFacade.retrieveSortedSessionsForAuthenticatedUser(eq("user-id"), any()))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForAuthenticatedUser("rc-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionsForGroupIdsShouldUseConsultantPathAndReturnNoContentWhenNoSessionsExist() {
    var responseDto = new GroupSessionListResponseDTO().sessions(List.of());
    var consultant = consultant();
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveSessionsForAuthenticatedConsultantByGroupIds(
            consultant, List.of("group-id"), roles))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForGroupIds(List.of("group-id"), "rc-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionsForGroupIdsShouldUseUserPathAndPassRocketChatCredentials() {
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var roles = Set.of(UserRole.USER.getValue());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(userWithRocketChatId());
    when(sessionListFacade.retrieveSessionsForAuthenticatedUserByGroupIds(
            eq("user-id"), eq(List.of("group-id")), any(), eq(roles)))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForGroupIds(List.of("group-id"), "rc-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    var credentialsCaptor = ArgumentCaptor.forClass(RocketChatCredentials.class);
    verify(sessionListFacade)
        .retrieveSessionsForAuthenticatedUserByGroupIds(
            eq("user-id"), eq(List.of("group-id")), credentialsCaptor.capture(), eq(roles));
    assertThat(credentialsCaptor.getValue().getRocketChatUserId()).isEqualTo("rc-user-id");
    assertThat(credentialsCaptor.getValue().getRocketChatToken()).isEqualTo("rc-token");
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionsForAuthenticatedConsultantShouldPassSessionQueryParameters() {
    var responseDto =
        new ConsultantSessionListResponseDTO()
            .sessions(List.of(new ConsultantSessionResponseDTO()))
            .offset(5)
            .count(10)
            .total(1);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant());
    when(sessionListFacade.retrieveSessionsDtoForAuthenticatedConsultant(any(), any()))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForAuthenticatedConsultant("rc-token", 5, 10, "all", 2);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    var queryCaptor = ArgumentCaptor.forClass(SessionListQueryParameter.class);
    verify(sessionListFacade)
        .retrieveSessionsDtoForAuthenticatedConsultant(eq(consultant()), queryCaptor.capture());
    assertThat(queryCaptor.getValue().getOffset()).isEqualTo(5);
    assertThat(queryCaptor.getValue().getCount()).isEqualTo(10);
    assertThat(queryCaptor.getValue().getSessionStatus()).isEqualTo(2);
  }

  @Test
  void getTeamSessionsForAuthenticatedConsultantShouldPassSessionQueryParameters() {
    var consultant = consultant();
    var responseDto =
        new ConsultantSessionListResponseDTO()
            .sessions(List.of(new ConsultantSessionResponseDTO()))
            .offset(3)
            .count(7)
            .total(1);
    when(userAccountProvider.retrieveValidatedTeamConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveTeamSessionsDtoForAuthenticatedConsultant(any(), any(), any()))
        .thenReturn(responseDto);

    var response = delegate.getTeamSessionsForAuthenticatedConsultant("rc-token", 3, 7, "all");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    var queryCaptor = ArgumentCaptor.forClass(SessionListQueryParameter.class);
    verify(sessionListFacade)
        .retrieveTeamSessionsDtoForAuthenticatedConsultant(
            eq(consultant), eq("rc-token"), queryCaptor.capture());
    assertThat(queryCaptor.getValue().getOffset()).isEqualTo(3);
    assertThat(queryCaptor.getValue().getCount()).isEqualTo(7);
  }

  @Test
  void getSessionForIdShouldFallbackToChatLookupForAdviceSeekerWhenNoSessionExists() {
    var emptySessionList = new GroupSessionListResponseDTO().sessions(List.of());
    var chatSessionList =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(userWithoutRocketChatId());
    when(sessionListFacade.retrieveSessionsForAuthenticatedUserBySessionIds(
            eq("user-id"), eq(List.of(1L)), any(), eq(Set.of(UserRole.USER.getValue()))))
        .thenReturn(emptySessionList);
    when(sessionListFacade.retrieveChatsForUserByChatIds(eq(List.of(1L)), any()))
        .thenReturn(chatSessionList);

    var response = delegate.getSessionForId(1L, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(chatSessionList);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(chatSessionList);
  }

  @Test
  void getSessionForIdShouldReturnConsultantSessionWithoutChatFallbackWhenSessionExists() {
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var consultant = consultant();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveSessionsForAuthenticatedConsultantBySessionIds(
            consultant, List.of(1L), roles))
        .thenReturn(responseDto);

    var response = delegate.getSessionForId(1L, "rc-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);
    verify(sessionListFacade)
        .retrieveSessionsForAuthenticatedConsultantBySessionIds(consultant, List.of(1L), roles);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionForIdShouldReturnNoContentWhenLookupFails() {
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userAccountProvider.retrieveValidatedUser()).thenThrow(new RuntimeException("boom"));

    var response = delegate.getSessionForId(1L, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verifyNoInteractions(consultantDataFacade);
  }

  @Test
  void getChatByIdShouldUseConsultantLookupAndReturnOk() {
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var consultant = consultant();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveChatsForConsultantByChatIds(any(), eq(List.of(1L)), any()))
        .thenReturn(responseDto);

    var response = delegate.getChatById("rc-token", 1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    var credentialsCaptor = ArgumentCaptor.forClass(RocketChatCredentials.class);
    verify(sessionListFacade)
        .retrieveChatsForConsultantByChatIds(
            eq(consultant), eq(List.of(1L)), credentialsCaptor.capture());
    assertThat(credentialsCaptor.getValue().getRocketChatUserId()).isEqualTo("rocket-chat-id");
    assertThat(credentialsCaptor.getValue().getRocketChatToken()).isEqualTo("rc-token");
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getChatByIdShouldUseUserLookupAndReturnNoContentWhenNoChatExists() {
    var responseDto = new GroupSessionListResponseDTO().sessions(List.of());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(userWithRocketChatId());
    when(sessionListFacade.retrieveChatsForUserByChatIds(eq(List.of(1L)), any()))
        .thenReturn(responseDto);

    var response = delegate.getChatById("rc-token", 1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void assignSessionShouldReturnForbiddenForNewSessionWithoutEnquiryAuthority() {
    when(sessionService.getSession(1L)).thenReturn(Optional.of(newSession()));
    when(authenticatedUser.getUserId()).thenReturn("consultant-id");
    when(authenticatedUser.getGrantedAuthorities())
        .thenReturn(Set.of(AuthorityValue.ASSIGN_CONSULTANT_TO_SESSION));

    var response = delegate.assignSession(1L, "assigned-consultant-id");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    verifyNoInteractions(assignSessionFacade);
  }

  @Test
  void assignSessionShouldReturnInternalServerErrorWhenSessionDoesNotExist() {
    when(sessionService.getSession(1L)).thenReturn(Optional.empty());

    var response = delegate.assignSession(1L, "assigned-consultant-id");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    verifyNoInteractions(assignSessionFacade, userAccountProvider, consultantService);
  }

  @Test
  void assignSessionShouldAssignExistingSessionAndReturnOk() {
    var session = inProgressSession();
    var consultantToAssign = consultant("assigned-consultant-id");
    var consultantToKeep = consultant("consultant-id");
    when(sessionService.getSession(1L)).thenReturn(Optional.of(session));
    when(authenticatedUser.getUserId()).thenReturn("consultant-id");
    when(userAccountProvider.retrieveValidatedConsultantById("assigned-consultant-id"))
        .thenReturn(consultantToAssign);
    when(consultantService.getConsultant("consultant-id"))
        .thenReturn(Optional.of(consultantToKeep));

    var response = delegate.assignSession(1L, "assigned-consultant-id");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(assignSessionFacade).assignSession(session, consultantToAssign, consultantToKeep);
  }

  @Test
  void removeFromSessionShouldRemoveConsultantAndReturnNoContent() {
    var consultantId = UUID.randomUUID();
    var consultantMap = Map.<String, Object>of("id", consultantId.toString());
    var sessionMap = Map.<String, Object>of("chatId", "chat-id");
    when(accountManager.findConsultant(consultantId.toString()))
        .thenReturn(Optional.of(consultantMap));
    when(messenger.findSession(1L)).thenReturn(Optional.of(sessionMap));
    when(consultantDtoMapper.chatIdOf(sessionMap)).thenReturn("chat-id");
    when(userDtoMapper.chatUserIdOf(consultantMap)).thenReturn("chat-user-id");
    when(messenger.removeUserFromSession("chat-user-id", "chat-id")).thenReturn(true);

    var response = delegate.removeFromSession(1L, consultantId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(messenger).removeUserFromSession("chat-user-id", "chat-id");
  }

  @Test
  void removeFromSessionShouldThrowNotFoundWhenConsultantDoesNotExist() {
    var consultantId = UUID.randomUUID();
    when(accountManager.findConsultant(consultantId.toString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.removeFromSession(1L, consultantId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void removeFromSessionShouldThrowInternalServerErrorWhenRemovalFails() {
    var consultantId = UUID.randomUUID();
    var consultantMap = Map.<String, Object>of("id", consultantId.toString());
    var sessionMap = Map.<String, Object>of("chatId", "chat-id");
    when(accountManager.findConsultant(consultantId.toString()))
        .thenReturn(Optional.of(consultantMap));
    when(messenger.findSession(1L)).thenReturn(Optional.of(sessionMap));
    when(consultantDtoMapper.chatIdOf(sessionMap)).thenReturn("chat-id");
    when(userDtoMapper.chatUserIdOf(consultantMap)).thenReturn("chat-user-id");
    when(messenger.removeUserFromSession("chat-user-id", "chat-id")).thenReturn(false);

    assertThatThrownBy(() -> delegate.removeFromSession(1L, consultantId))
        .isInstanceOf(InternalServerErrorException.class);
  }

  @Test
  void fetchSessionForConsultantShouldReturnSessionDtoFromService() {
    var consultant = consultant();
    var sessionDto = new ConsultantSessionDTO().id(1L);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionService.fetchSessionForConsultant(1L, consultant)).thenReturn(sessionDto);

    var response = delegate.fetchSessionForConsultant(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(sessionDto);
  }

  @Test
  void archiveAndDearchiveSessionShouldDelegateAndReturnOk() {
    var archiveResponse = delegate.archiveSession(1L);
    var dearchiveResponse = delegate.dearchiveSession(2L);

    assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dearchiveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(sessionArchiveService).archiveSession(1L);
    verify(sessionArchiveService).dearchiveSession(2L);
  }

  private User userWithoutRocketChatId() {
    return User.builder().userId("user-id").username("user").email("user@example.com").build();
  }

  private User userWithRocketChatId() {
    return User.builder()
        .userId("user-id")
        .username("user")
        .email("user@example.com")
        .rcUserId("rc-user-id")
        .build();
  }

  private Consultant consultant() {
    return consultant("consultant-id");
  }

  private Consultant consultant(String id) {
    return Consultant.builder()
        .id(id)
        .rocketChatId("rocket-chat-id")
        .username("consultant")
        .firstName("Con")
        .lastName("Sultant")
        .email("consultant@example.com")
        .build();
  }

  private Session newSession() {
    return session(Session.SessionStatus.NEW);
  }

  private Session inProgressSession() {
    return session(Session.SessionStatus.IN_PROGRESS);
  }

  private Session session(Session.SessionStatus status) {
    return Session.builder()
        .id(1L)
        .registrationType(Session.RegistrationType.REGISTERED)
        .postcode("10115")
        .status(status)
        .build();
  }
}
