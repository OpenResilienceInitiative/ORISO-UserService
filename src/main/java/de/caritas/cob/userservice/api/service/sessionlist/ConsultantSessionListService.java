package de.caritas.cob.userservice.api.service.sessionlist;

import static java.util.Collections.emptyList;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.container.SessionListQueryParameter;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.session.ConsultantSessionQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class ConsultantSessionListService {

  private final @NonNull ConsultantSessionQueryService consultantSessionQueryService;
  private final @NonNull ChatService chatService;
  private final @NonNull ConsultantSessionEnricher consultantSessionEnricher;
  private final @NonNull ConsultantChatEnricher consultantChatEnricher;

  /**
   * @param consultant {@link Consultant}
   * @param roomIds chat room IDs
   * @param roles roles of the consultant
   * @return List of {@link ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> retrieveSessionsForConsultantAndRoomIds(
      Consultant consultant, List<String> roomIds, Set<String> roles) {
    var matrixRoomIds = new HashSet<>(roomIds);
    var sessions =
        consultantSessionQueryService.getAllowedSessionsByConsultantAndRoomIds(
            consultant, matrixRoomIds, roles);
    var chats = chatService.getChatSessionsForConsultantByRoomIds(matrixRoomIds);

    return mergeConsultantSessionsAndChats(consultant, sessions, chats);
  }

  /**
   * @param consultant {@link Consultant}
   * @param sessionIds session IDs
   * @param roles roles of the consultant
   * @return List of {@link ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> retrieveSessionsForConsultantAndSessionIds(
      Consultant consultant, List<Long> sessionIds, Set<String> roles) {
    var uniqueSessionIds = new HashSet<>(sessionIds);
    var sessions =
        consultantSessionQueryService.getSessionsByIds(consultant, uniqueSessionIds, roles);
    var matrixRoomIds =
        sessions.stream()
            .map(sessionResponse -> sessionResponse.getSession().getMatrixRoomId())
            .collect(Collectors.toSet());
    var chats = chatService.getChatSessionsForConsultantByRoomIds(matrixRoomIds);

    return mergeConsultantSessionsAndChats(consultant, sessions, chats);
  }

  /**
   * Loads anonymous Live Chat queue entries by id using the queue's topic-based, cross-tenant
   * visibility (#774). Used as the open-path fallback when {@link
   * #retrieveSessionsForConsultantAndSessionIds} finds nothing, so a live chat request the
   * consultant can see in the queue can also be opened and accepted.
   */
  public List<ConsultantSessionResponseDTO>
      retrieveAnonymousLiveChatEnquiriesForConsultantBySessionIds(
          Consultant consultant, List<Long> sessionIds) {
    var uniqueSessionIds = new HashSet<>(sessionIds);
    return consultantSessionQueryService.getVisibleAnonymousLiveChatEnquiriesByIds(
        consultant, uniqueSessionIds);
  }

  /**
   * Loads a cross-tenant session the consultant is directly assigned to (#774 follow-up). Used as
   * the open-path fallback after a cross-tenant live chat is accepted, so routing to the accepted
   * conversation resolves it instead of 204-ing.
   */
  public List<ConsultantSessionResponseDTO>
      retrieveDirectlyAssignedSessionsForConsultantBySessionIds(
          Consultant consultant, List<Long> sessionIds) {
    var uniqueSessionIds = new HashSet<>(sessionIds);
    return consultantSessionQueryService.getDirectlyAssignedSessionsByIdsCrossTenant(
        consultant, uniqueSessionIds);
  }

  public List<ConsultantSessionResponseDTO> retrieveChatsForConsultantAndChatIds(
      Consultant consultant, List<Long> chatIds) {
    log.info(
        "🔍 ConsultantSessionListService.retrieveChatsForConsultantAndChatIds - consultant: {}, chatIds: {}",
        consultant.getUsername(),
        chatIds);

    var uniqueChatIds = new HashSet<>(chatIds);
    log.info("🔍 Unique chat IDs: {}", uniqueChatIds);

    var chats = chatService.getChatSessionsForConsultantByIds(uniqueChatIds);
    log.info("🔍 Retrieved {} chats from ChatService", chats.size());

    var result = updateConsultantChatValues(chats, consultant);
    log.info("🔍 After updateConsultantChatValues: {} chats", result.size());

    return result;
  }

  /**
   * Returns a list of {@link ConsultantSessionResponseDTO} for the specified consultant id and
   * status.
   *
   * @param consultant {@link Consultant}
   * @param sessionListQueryParameter session list query parameters as {@link
   *     SessionListQueryParameter}
   * @return the response dto
   */
  public List<ConsultantSessionResponseDTO> retrieveSessionsForAuthenticatedConsultant(
      Consultant consultant, SessionListQueryParameter sessionListQueryParameter) {

    List<ConsultantSessionResponseDTO> sessions =
        retrieveSessionsForStatus(consultant, sessionListQueryParameter.getSessionStatus());
    List<ConsultantSessionResponseDTO> chats = new ArrayList<>();

    if (SessionStatus.isStatusValueInProgress(sessionListQueryParameter.getSessionStatus())) {
      chats = chatService.getChatsForConsultant(consultant);
    }

    return mergeConsultantSessionsAndChats(consultant, sessions, chats);
  }

  private List<ConsultantSessionResponseDTO> retrieveSessionsForStatus(
      Consultant consultant, Integer status) {
    var sessionStatus = getVerifiedSessionStatus(status);

    if (sessionStatus.equals(SessionStatus.NEW)) {
      return consultantSessionQueryService.getRegisteredEnquiriesForConsultant(consultant);
    }
    if (sessionStatus.equals(SessionStatus.IN_PROGRESS)) {
      return consultantSessionQueryService.getActiveAndDoneSessionsForConsultant(consultant);
    }
    return emptyList();
  }

  private SessionStatus getVerifiedSessionStatus(Integer status) {
    return SessionStatus.valueOf(status)
        .orElseThrow(
            () -> new BadRequestException(String.format("Invalid session status %s ", status)));
  }

  /**
   * Returns a list of {@link ConsultantSessionResponseDTO} for the specified consultant id.
   *
   * @param consultant the {@link Consultant}
   * @param sessionListQueryParameter session list query parameters as {@link
   *     SessionListQueryParameter}
   * @return a {@link ConsultantSessionListResponseDTO} with a {@link List} of {@link
   *     ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> retrieveTeamSessionsForAuthenticatedConsultant(
      Consultant consultant, SessionListQueryParameter sessionListQueryParameter) {

    // Get team sessions (Session entities)
    List<ConsultantSessionResponseDTO> teamSessions =
        consultantSessionQueryService.getTeamSessionsForConsultant(consultant);

    // MATRIX MIGRATION: Also get chats for group chats (Chat entities with topic field)
    // Group chats created via the new flow have BOTH Session and Chat entities
    List<ConsultantSessionResponseDTO> teamChats = chatService.getChatsForConsultant(consultant);

    // Merge sessions and chats
    List<ConsultantSessionResponseDTO> allTeamSessions =
        mergeConsultantSessionsAndChats(consultant, teamSessions, teamChats);

    sortSessionsByLastMessageDateDesc(allTeamSessions);

    return allTeamSessions;
  }

  private List<ConsultantSessionResponseDTO> mergeConsultantSessionsAndChats(
      Consultant consultant,
      List<ConsultantSessionResponseDTO> sessions,
      List<ConsultantSessionResponseDTO> chats) {
    List<ConsultantSessionResponseDTO> allSessions = new ArrayList<>();

    // Enrich sessions and chats
    List<ConsultantSessionResponseDTO> enrichedSessions = emptyList();
    List<ConsultantSessionResponseDTO> enrichedChats = emptyList();

    if (isNotEmpty(sessions)) {
      enrichedSessions = updateConsultantSessionValues(sessions);
    }

    if (isNotEmpty(chats)) {
      enrichedChats = updateConsultantChatValues(chats, consultant);
    }

    // A group chat has both a Session and a Chat with the same Matrix room ID.
    var chatsByMatrixRoomId =
        enrichedChats.stream()
            .filter(chat -> chat.getChat() != null && chat.getChat().getMatrixRoomId() != null)
            .collect(Collectors.toMap(chat -> chat.getChat().getMatrixRoomId(), chat -> chat));

    // Add sessions, merging with matching chats
    for (ConsultantSessionResponseDTO session : enrichedSessions) {
      if (session.getSession() != null && session.getSession().getMatrixRoomId() != null) {
        var matchingChat = chatsByMatrixRoomId.get(session.getSession().getMatrixRoomId());
        if (matchingChat != null) {
          // Merge: session already has session data, add chat data from matching chat
          session.setChat(matchingChat.getChat());
          chatsByMatrixRoomId.remove(session.getSession().getMatrixRoomId());
        }
      }
      allSessions.add(session);
    }

    allSessions.addAll(chatsByMatrixRoomId.values());

    return allSessions;
  }

  private void sortSessionsByLastMessageDateDesc(List<ConsultantSessionResponseDTO> sessions) {
    sessions.sort(
        Comparator.comparing(
            ConsultantSessionResponseDTO::getLatestMessage,
            Comparator.nullsLast(Comparator.reverseOrder())));
  }

  private List<ConsultantSessionResponseDTO> updateConsultantSessionValues(
      List<ConsultantSessionResponseDTO> sessions) {
    return this.consultantSessionEnricher.updateRequiredConsultantSessionValues(sessions);
  }

  private List<ConsultantSessionResponseDTO> updateConsultantChatValues(
      List<ConsultantSessionResponseDTO> chats, Consultant consultant) {
    return this.consultantChatEnricher.updateRequiredConsultantChatValues(chats, consultant);
  }
}
