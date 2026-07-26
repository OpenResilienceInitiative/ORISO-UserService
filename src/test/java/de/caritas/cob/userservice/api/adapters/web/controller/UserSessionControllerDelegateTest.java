package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
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
import jakarta.ws.rs.InternalServerErrorException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
  @Mock private AssignEnquiryFacade assignEnquiryFacade;
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
  void getSessionsForAuthenticatedUserShouldReturnOk() {
    var responseDto =
        new UserSessionListResponseDTO().sessions(List.of(new UserSessionResponseDTO()));
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveSortedSessionsForAuthenticatedUser(eq("user-id")))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForAuthenticatedUser();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);

    verify(sessionListFacade).retrieveSortedSessionsForAuthenticatedUser("user-id");
  }

  @Test
  void getSessionsForAuthenticatedUserShouldReturnNoContentWhenNoSessionsExist() {
    var responseDto = new UserSessionListResponseDTO().sessions(List.of());
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveSortedSessionsForAuthenticatedUser(eq("user-id")))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForAuthenticatedUser();

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

    var response = delegate.getSessionsForGroupIds(List.of("group-id"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionsForGroupIdsShouldUseUserPath() {
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var roles = Set.of(UserRole.USER.getValue());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveSessionsForAuthenticatedUserByGroupIds(
            eq("user-id"), eq(List.of("group-id")), eq(roles)))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForGroupIds(List.of("group-id"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    verify(sessionListFacade)
        .retrieveSessionsForAuthenticatedUserByGroupIds("user-id", List.of("group-id"), roles);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionsForGroupIdsShouldNotRequireChatIdentityMetadata() {
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var roles = Set.of(UserRole.USER.getValue());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveSessionsForAuthenticatedUserByGroupIds(
            eq("user-id"), eq(List.of("group-id")), eq(roles)))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForGroupIds(List.of("group-id"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(sessionListFacade)
        .retrieveSessionsForAuthenticatedUserByGroupIds("user-id", List.of("group-id"), roles);
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

    var response = delegate.getSessionsForAuthenticatedConsultant(5, 10, "all", 2);

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
    when(sessionListFacade.retrieveTeamSessionsDtoForAuthenticatedConsultant(any(), any()))
        .thenReturn(responseDto);

    var response = delegate.getTeamSessionsForAuthenticatedConsultant(3, 7, "all");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    var queryCaptor = ArgumentCaptor.forClass(SessionListQueryParameter.class);
    verify(sessionListFacade)
        .retrieveTeamSessionsDtoForAuthenticatedConsultant(eq(consultant), queryCaptor.capture());
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
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveSessionsForAuthenticatedUserBySessionIds(
            eq("user-id"), eq(List.of(1L)), eq(Set.of(UserRole.USER.getValue()))))
        .thenReturn(emptySessionList);
    when(sessionListFacade.retrieveChatsForUserByChatIds(eq("user-id"), eq(List.of(1L))))
        .thenReturn(chatSessionList);

    var response = delegate.getSessionForId(1L);

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

    var response = delegate.getSessionForId(1L);

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

    var response = delegate.getSessionForId(1L);

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
    when(sessionListFacade.retrieveChatsForConsultantByChatIds(any(), eq(List.of(1L))))
        .thenReturn(responseDto);

    var response = delegate.getChatById(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);

    verify(sessionListFacade).retrieveChatsForConsultantByChatIds(consultant, List.of(1L));
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getChatByIdShouldUseUserLookupAndReturnNoContentWhenNoChatExists() {
    var responseDto = new GroupSessionListResponseDTO().sessions(List.of());
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveChatsForUserByChatIds(eq("user-id"), eq(List.of(1L))))
        .thenReturn(responseDto);

    var response = delegate.getChatById(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getChatByIdShouldNotRequireChatIdentityMetadata() {
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    when(authenticatedUser.isConsultant()).thenReturn(false);
    when(userAccountProvider.retrieveValidatedUser()).thenReturn(user());
    when(sessionListFacade.retrieveChatsForUserByChatIds(eq("user-id"), eq(List.of(1L))))
        .thenReturn(responseDto);

    var response = delegate.getChatById(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(sessionListFacade).retrieveChatsForUserByChatIds("user-id", List.of(1L));
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

  @Test
  void getSessionsForGroupIds_consultantPathWithSessions_returnsOkWithList() {
    // Consultants resolve group sessions through the consultant-specific facade path.
    var responseDto =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var consultant = consultant();
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveSessionsForAuthenticatedConsultantByGroupIds(
            consultant, List.of("group-id"), roles))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForGroupIds(List.of("group-id"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(responseDto);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(responseDto);
  }

  @Test
  void getSessionForId_consultantChatFallbackWhenSessionLookupEmpty_returnsChat() {
    // Consultants fall back to chat lookup when no session matches the room id.
    var emptySessionList = new GroupSessionListResponseDTO().sessions(List.of());
    var chatSessionList =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var consultant = consultant();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveSessionsForAuthenticatedConsultantBySessionIds(
            consultant, List.of(1L), roles))
        .thenReturn(emptySessionList);
    when(sessionListFacade.retrieveChatsForConsultantByChatIds(eq(consultant), eq(List.of(1L))))
        .thenReturn(chatSessionList);

    var response = delegate.getSessionForId(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(chatSessionList);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(chatSessionList);
  }

  @Test
  void getSessionForId_anonymousLiveChatFallbackWhenSessionLookupEmpty_returnsEnquiry() {
    // #774: a live chat request visible to the consultant by topic (but not assigned/agency-owned)
    // must open through the anonymous queue fallback rather than 204.
    var emptySessionList = new GroupSessionListResponseDTO().sessions(List.of());
    var anonymousList =
        new GroupSessionListResponseDTO().sessions(List.of(new GroupSessionResponseDTO()));
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var consultant = consultant();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveSessionsForAuthenticatedConsultantBySessionIds(
            consultant, List.of(1L), roles))
        .thenReturn(emptySessionList);
    when(sessionListFacade.retrieveAnonymousLiveChatEnquiriesForConsultantBySessionIds(
            consultant, List.of(1L)))
        .thenReturn(anonymousList);

    var response = delegate.getSessionForId(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(anonymousList);
    // The chat fallback must not run once the anonymous enquiry resolves.
    verify(sessionListFacade, never()).retrieveChatsForConsultantByChatIds(any(), any());
  }

  @Test
  void getSessionForId_bothSessionAndChatEmpty_returnsNoContent() {
    // Empty session and chat lookups yield no content for the requested room.
    var emptySessionList = new GroupSessionListResponseDTO().sessions(List.of());
    var emptyChatList = new GroupSessionListResponseDTO().sessions(List.of());
    var roles = Set.of(UserRole.CONSULTANT.getValue());
    var consultant = consultant();
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getRoles()).thenReturn(roles);
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant);
    when(sessionListFacade.retrieveSessionsForAuthenticatedConsultantBySessionIds(
            consultant, List.of(1L), roles))
        .thenReturn(emptySessionList);
    when(sessionListFacade.retrieveChatsForConsultantByChatIds(eq(consultant), eq(List.of(1L))))
        .thenReturn(emptyChatList);

    var response = delegate.getSessionForId(1L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(consultantDataFacade).addConsultantDisplayNameToSessionList(emptyChatList);
  }

  @Test
  void getSessionsForAuthenticatedConsultant_emptySessions_returnsNoContent() {
    // Consultants with no matching sessions receive an empty response.
    var responseDto = new ConsultantSessionListResponseDTO().sessions(List.of());
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant());
    when(sessionListFacade.retrieveSessionsDtoForAuthenticatedConsultant(any(), any()))
        .thenReturn(responseDto);

    var response = delegate.getSessionsForAuthenticatedConsultant(0, 10, "all", 2);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void getSessionsForAuthenticatedConsultant_invalidFilter_returnsNoContent() {
    // Unknown session filters skip facade lookup and return no content.
    when(userAccountProvider.retrieveValidatedConsultant()).thenReturn(consultant());

    var response = delegate.getSessionsForAuthenticatedConsultant(0, 10, "unknown-filter", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verifyNoInteractions(sessionListFacade);
  }

  @Test
  void getTeamSessionsForAuthenticatedConsultant_emptySessions_returnsNoContent() {
    // Team consultants with no team sessions receive an empty response.
    var responseDto = new ConsultantSessionListResponseDTO().sessions(List.of());
    when(userAccountProvider.retrieveValidatedTeamConsultant()).thenReturn(consultant());
    when(sessionListFacade.retrieveTeamSessionsDtoForAuthenticatedConsultant(any(), any()))
        .thenReturn(responseDto);

    var response = delegate.getTeamSessionsForAuthenticatedConsultant(0, 10, "all");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void getTeamSessionsForAuthenticatedConsultant_invalidFilter_returnsNoContent() {
    // Unknown team session filters skip facade lookup and return no content.
    when(userAccountProvider.retrieveValidatedTeamConsultant()).thenReturn(consultant());

    var response = delegate.getTeamSessionsForAuthenticatedConsultant(0, 10, "unknown-filter");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verifyNoInteractions(sessionListFacade);
  }

  @Test
  void assignSession_newSessionWithEnquiryAuthority_success() {
    // New enquiries may be assigned when the caller holds enquiry assignment authority.
    var session = newSession();
    var consultantToAssign = consultant("assigned-consultant-id");
    when(sessionService.getSession(1L)).thenReturn(Optional.of(session));
    when(authenticatedUser.getUserId()).thenReturn("consultant-id");
    when(authenticatedUser.getGrantedAuthorities())
        .thenReturn(Set.of(AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY));
    when(userAccountProvider.retrieveValidatedConsultantById("assigned-consultant-id"))
        .thenReturn(consultantToAssign);
    var response = delegate.assignSession(1L, "assigned-consultant-id");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(assignEnquiryFacade).assignRegisteredEnquiry(session, consultantToAssign);
    verifyNoInteractions(assignSessionFacade, consultantService);
  }

  @Test
  void removeFromSession_sessionNotFound_throwsNotFoundException() {
    // Removing a consultant requires the target session to exist in chat.
    var consultantId = UUID.randomUUID();
    var consultantMap = Map.<String, Object>of("id", consultantId.toString());
    when(accountManager.findConsultant(consultantId.toString()))
        .thenReturn(Optional.of(consultantMap));
    when(messenger.findSession(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.removeFromSession(1L, consultantId))
        .isInstanceOf(NotFoundException.class);
  }

  private User user() {
    return User.builder().userId("user-id").username("user").email("user@example.com").build();
  }

  private Consultant consultant() {
    return consultant("consultant-id");
  }

  private Consultant consultant(String id) {
    return Consultant.builder()
        .id(id)
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
