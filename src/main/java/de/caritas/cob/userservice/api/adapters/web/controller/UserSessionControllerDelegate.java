package de.caritas.cob.userservice.api.adapters.web.controller;

import static java.util.Collections.singletonList;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.container.SessionListQueryParameter;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignSessionFacade;
import de.caritas.cob.userservice.api.facade.sessionlist.SessionListFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.session.SessionFilter;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import jakarta.ws.rs.InternalServerErrorException;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class UserSessionControllerDelegate {

  private final @NonNull UserAccountService userAccountProvider;
  private final @NonNull SessionService sessionService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull SessionListFacade sessionListFacade;
  private final @NonNull AssignEnquiryFacade assignEnquiryFacade;
  private final @NonNull AssignSessionFacade assignSessionFacade;
  private final @NonNull ConsultantDataFacade consultantDataFacade;
  private final @NonNull SessionArchiveService sessionArchiveService;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull Messaging messenger;
  private final @NonNull ConsultantDtoMapper consultantDtoMapper;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull ConsultantService consultantService;

  ResponseEntity<UserSessionListResponseDTO> getSessionsForAuthenticatedUser() {
    var user = this.userAccountProvider.retrieveValidatedUser();

    var userSessionsDTO =
        sessionListFacade.retrieveSortedSessionsForAuthenticatedUser(user.getUserId());

    consultantDataFacade.addConsultantDisplayNameToSessionList(userSessionsDTO);

    return isNotEmpty(userSessionsDTO.getSessions())
        ? new ResponseEntity<>(userSessionsDTO, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  ResponseEntity<GroupSessionListResponseDTO> getSessionsForGroupIds(List<String> roomIds) {
    GroupSessionListResponseDTO groupSessionList;
    if (authenticatedUser.isConsultant()) {
      var consultant = userAccountProvider.retrieveValidatedConsultant();
      groupSessionList =
          sessionListFacade.retrieveSessionsForAuthenticatedConsultantByGroupIds(
              consultant, roomIds, authenticatedUser.getRoles());
    } else {
      var user = userAccountProvider.retrieveValidatedUser();
      groupSessionList =
          sessionListFacade.retrieveSessionsForAuthenticatedUserByGroupIds(
              user.getUserId(), roomIds, authenticatedUser.getRoles());
    }

    consultantDataFacade.addConsultantDisplayNameToSessionList(groupSessionList);

    return isNotEmpty(groupSessionList.getSessions())
        ? new ResponseEntity<>(groupSessionList, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  ResponseEntity<GroupSessionListResponseDTO> getSessionForId(Long sessionId) {
    log.info("GET /users/sessions/room/{} - sessionId: {}", sessionId, sessionId);

    try {
      GroupSessionListResponseDTO groupSessionList;
      if (authenticatedUser.isConsultant()) {
        groupSessionList = getSessionOrChatForConsultant(sessionId);
      } else {
        groupSessionList = getSessionOrChatForUser(sessionId);
      }

      consultantDataFacade.addConsultantDisplayNameToSessionList(groupSessionList);

      return isNotEmpty(groupSessionList.getSessions())
          ? new ResponseEntity<>(groupSessionList, HttpStatus.OK)
          : new ResponseEntity<>(HttpStatus.NO_CONTENT);
    } catch (Exception e) {
      log.error("Failed to load session room {}: {}", sessionId, e.getMessage(), e);
      return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
  }

  ResponseEntity<GroupSessionListResponseDTO> getChatById(Long chatId) {
    GroupSessionListResponseDTO groupSessionList;
    if (authenticatedUser.isConsultant()) {
      var consultant = userAccountProvider.retrieveValidatedConsultant();
      groupSessionList =
          sessionListFacade.retrieveChatsForConsultantByChatIds(consultant, singletonList(chatId));
    } else {
      var user = userAccountProvider.retrieveValidatedUser();
      groupSessionList =
          sessionListFacade.retrieveChatsForUserByChatIds(user.getUserId(), singletonList(chatId));
    }

    consultantDataFacade.addConsultantDisplayNameToSessionList(groupSessionList);

    return isNotEmpty(groupSessionList.getSessions())
        ? new ResponseEntity<>(groupSessionList, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  ResponseEntity<ConsultantSessionListResponseDTO> getSessionsForAuthenticatedConsultant(
      Integer offset, Integer count, String filter, Integer status) {
    var consultant = this.userAccountProvider.retrieveValidatedConsultant();

    ConsultantSessionListResponseDTO consultantSessionListResponseDTO = null;
    var optionalSessionFilter = SessionFilter.getByValue(filter);
    if (optionalSessionFilter.isPresent()) {
      var sessionListQueryParameter =
          SessionListQueryParameter.builder()
              .sessionStatus(status)
              .count(count)
              .offset(offset)
              .sessionFilter(optionalSessionFilter.get())
              .build();

      consultantSessionListResponseDTO =
          sessionListFacade.retrieveSessionsDtoForAuthenticatedConsultant(
              consultant, sessionListQueryParameter);
    }

    return nonNull(consultantSessionListResponseDTO)
            && isNotEmpty(consultantSessionListResponseDTO.getSessions())
        ? new ResponseEntity<>(consultantSessionListResponseDTO, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  ResponseEntity<ConsultantSessionListResponseDTO> getTeamSessionsForAuthenticatedConsultant(
      Integer offset, Integer count, String filter) {
    var consultant = this.userAccountProvider.retrieveValidatedTeamConsultant();

    ConsultantSessionListResponseDTO teamSessionListDTO = null;
    var optionalSessionFilter = SessionFilter.getByValue(filter);
    if (optionalSessionFilter.isPresent()) {
      var sessionListQueryParameter =
          SessionListQueryParameter.builder()
              .count(count)
              .offset(offset)
              .sessionFilter(optionalSessionFilter.get())
              .build();

      teamSessionListDTO =
          sessionListFacade.retrieveTeamSessionsDtoForAuthenticatedConsultant(
              consultant, sessionListQueryParameter);
    }

    return nonNull(teamSessionListDTO) && isNotEmpty(teamSessionListDTO.getSessions())
        ? new ResponseEntity<>(teamSessionListDTO, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  ResponseEntity<Void> assignSession(Long sessionId, String consultantId) {
    var session = sessionService.getSession(sessionId);
    if (session.isEmpty()) {
      log.error("Internal Server Error: Session with id {} not found.", sessionId);

      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    var userId = authenticatedUser.getUserId();
    var isNewEnquiry = session.get().getStatus().equals(SessionStatus.NEW);
    if (isNewEnquiry
        && !authenticatedUser
            .getGrantedAuthorities()
            .contains(AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY)) {
      LogService.logForbidden(
          String.format(
              "The calling consultant with id %s does not have the authority to assign the enquiry to a consultant.",
              userId));

      return new ResponseEntity<>(HttpStatus.FORBIDDEN);
    }

    var consultantToAssign = userAccountProvider.retrieveValidatedConsultantById(consultantId);
    if (isNewEnquiry) {
      assignEnquiryFacade.assignRegisteredEnquiry(session.get(), consultantToAssign);
      return new ResponseEntity<>(HttpStatus.OK);
    }

    var consultantToKeep = consultantService.getConsultant(userId).orElse(null);
    assignSessionFacade.assignSession(session.get(), consultantToAssign, consultantToKeep);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> removeFromSession(Long sessionId, UUID consultantId) {
    var consultantMap =
        accountManager
            .findConsultant(consultantId.toString())
            .orElseThrow(
                () -> new NotFoundException("Consultant (%s) not found", consultantId.toString()));

    var sessionMap =
        messenger
            .findSession(sessionId)
            .orElseThrow(() -> new NotFoundException("Session (%s) not found", sessionId));

    var chatId = consultantDtoMapper.chatIdOf(sessionMap);
    var chatUserId = userDtoMapper.chatUserIdOf(consultantMap);
    if (!messenger.removeUserFromSession(chatUserId, chatId)) {
      var message =
          String.format(
              "Could not remove consultant (%s) from session (%s)", consultantId, sessionId);
      throw new InternalServerErrorException(message);
    }

    return ResponseEntity.noContent().build();
  }

  ResponseEntity<ConsultantSessionDTO> fetchSessionForConsultant(Long sessionId) {
    var consultant = this.userAccountProvider.retrieveValidatedConsultant();
    var consultantSessionDTO = sessionService.fetchSessionForConsultant(sessionId, consultant);
    return new ResponseEntity<>(consultantSessionDTO, HttpStatus.OK);
  }

  ResponseEntity<Void> archiveSession(Long sessionId) {
    this.sessionArchiveService.archiveSession(sessionId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  ResponseEntity<Void> dearchiveSession(Long sessionId) {
    this.sessionArchiveService.dearchiveSession(sessionId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  private GroupSessionListResponseDTO getSessionOrChatForConsultant(Long sessionId) {
    var consultant = userAccountProvider.retrieveValidatedConsultant();
    log.info("User is CONSULTANT: {}, id: {}", consultant.getUsername(), consultant.getId());

    log.info("Step 1: Trying to find as SESSION with ID: {}", sessionId);
    var groupSessionList =
        sessionListFacade.retrieveSessionsForAuthenticatedConsultantBySessionIds(
            consultant, singletonList(sessionId), authenticatedUser.getRoles());

    log.info(
        "Step 1 result: {} sessions found",
        groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);

    // Step 1b (#774): an anonymous Live Chat request is made visible to the consultant by topic
    // across tenants, but Step 1 only resolves sessions the consultant is assigned to or shares an
    // agency with (tenant-filtered). Without this fallback a consultant sees a live chat request in
    // the queue but gets 204 opening it, so it can never be accepted. Same topic-based visibility
    // as the queue itself.
    if (groupSessionList.getSessions() == null || groupSessionList.getSessions().isEmpty()) {
      log.info(
          "Step 1b: No assigned session found, trying anonymous Live Chat queue for ID: {}",
          sessionId);
      var anonymousLiveChat =
          sessionListFacade.retrieveAnonymousLiveChatEnquiriesForConsultantBySessionIds(
              consultant, singletonList(sessionId));
      if (anonymousLiveChat != null
          && anonymousLiveChat.getSessions() != null
          && !anonymousLiveChat.getSessions().isEmpty()) {
        log.info(
            "Step 1b result: anonymous Live Chat enquiry {} resolved for consultant", sessionId);
        return anonymousLiveChat;
      }
    }

    // Step 1c (#774 follow-up): once a cross-tenant live chat is accepted it becomes IN_PROGRESS
    // and
    // keeps the asker's tenant, so Step 1 (tenant-filtered) and the anonymous-NEW fallback above no
    // longer match it. Without this, routing to the just-accepted conversation 204s and the chat
    // never opens. Resolve it across tenants, but only when this consultant is its directly
    // assigned
    // advisor — no agency/topic widening, and tenant ownership is unchanged.
    if (groupSessionList.getSessions() == null || groupSessionList.getSessions().isEmpty()) {
      log.info("Step 1c: Trying cross-tenant directly-assigned session for ID: {}", sessionId);
      var assignedCrossTenant =
          sessionListFacade.retrieveDirectlyAssignedSessionsForConsultantBySessionIds(
              consultant, singletonList(sessionId));
      if (assignedCrossTenant != null
          && assignedCrossTenant.getSessions() != null
          && !assignedCrossTenant.getSessions().isEmpty()) {
        log.info("Step 1c result: cross-tenant assigned session {} resolved", sessionId);
        return assignedCrossTenant;
      }
    }

    if (groupSessionList.getSessions() == null || groupSessionList.getSessions().isEmpty()) {
      log.info("Step 2: No session found, trying to find as CHAT with ID: {}", sessionId);
      groupSessionList =
          sessionListFacade.retrieveChatsForConsultantByChatIds(
              consultant, singletonList(sessionId));

      log.info(
          "Step 2 result: {} chats found",
          groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);
    }
    return groupSessionList;
  }

  private GroupSessionListResponseDTO getSessionOrChatForUser(Long sessionId) {
    var user = userAccountProvider.retrieveValidatedUser();
    log.info("User is USER/ASKER: {}, id: {}", user.getUsername(), user.getUserId());

    log.info("Step 1: Trying to find as SESSION with ID: {}", sessionId);
    var groupSessionList =
        sessionListFacade.retrieveSessionsForAuthenticatedUserBySessionIds(
            user.getUserId(), singletonList(sessionId), authenticatedUser.getRoles());

    log.info(
        "Step 1 result: {} sessions found",
        groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);

    if (groupSessionList.getSessions() == null || groupSessionList.getSessions().isEmpty()) {
      log.info("Step 2: No session found, trying to find as CHAT with ID: {}", sessionId);
      groupSessionList =
          sessionListFacade.retrieveChatsForUserByChatIds(
              user.getUserId(), singletonList(sessionId));

      log.info(
          "Step 2 result: {} chats found",
          groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);
    }
    return groupSessionList;
  }
}
