package de.caritas.cob.userservice.api.service.sessionlist;

import static java.util.Collections.emptyList;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.container.SessionListQueryParameter;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.session.SessionService;
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

  private final @NonNull SessionService sessionService;
  private final @NonNull ChatService chatService;
  private final @NonNull ConsultantSessionEnricher consultantSessionEnricher;
  private final @NonNull ConsultantChatEnricher consultantChatEnricher;
  private final RocketChatCredentials rocketChatCredentials;

  /**
   * @param consultant {@link Consultant}
   * @param rcGroupIds rocket chat group IDs
   * @param roles roles of the consultant
   * @return List of {@link ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> retrieveSessionsForConsultantAndGroupIds(
      Consultant consultant, List<String> rcGroupIds, Set<String> roles) {
    var groupIds = new HashSet<>(rcGroupIds);
    var sessions =
        sessionService.getAllowedSessionsByConsultantAndGroupIds(consultant, groupIds, roles);
    var chats = chatService.getChatSessionsForConsultantByGroupIds(groupIds);

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
    var sessions = sessionService.getSessionsByIds(consultant, uniqueSessionIds, roles);
    var groupIds =
        sessions.stream()
            .map(sessionResponse -> sessionResponse.getSession().getGroupId())
            .collect(Collectors.toSet());
    var chats = chatService.getChatSessionsForConsultantByGroupIds(groupIds);

    return mergeConsultantSessionsAndChats(consultant, sessions, chats);
  }

  public List<ConsultantSessionResponseDTO> retrieveChatsForConsultantAndChatIds(
      Consultant consultant, List<Long> chatIds, String rcAuthToken) {
    log.info(
        "🔍 ConsultantSessionListService.retrieveChatsForConsultantAndChatIds - consultant: {}, chatIds: {}",
        consultant.getUsername(),
        chatIds);

    var uniqueChatIds = new HashSet<>(chatIds);
    log.info("🔍 Unique chat IDs: {}", uniqueChatIds);

    var chats = chatService.getChatSessionsForConsultantByIds(uniqueChatIds);
    log.info("🔍 Retrieved {} chats from ChatService", chats.size());

    var result = updateConsultantChatValues(chats, rcAuthToken, consultant);
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
      return this.sessionService.getRegisteredEnquiriesForConsultant(consultant);
    }
    if (sessionStatus.equals(SessionStatus.IN_PROGRESS)) {
      return this.sessionService.getActiveAndDoneSessionsForConsultant(consultant);
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
   * @param rcAuthToken the Rocket.Chat auth token
   * @param sessionListQueryParameter session list query parameters as {@link
   *     SessionListQueryParameter}
   * @return a {@link ConsultantSessionListResponseDTO} with a {@link List} of {@link
   *     ConsultantSessionResponseDTO}
   */
  public List<ConsultantSessionResponseDTO> retrieveTeamSessionsForAuthenticatedConsultant(
      Consultant consultant,
      String rcAuthToken,
      SessionListQueryParameter sessionListQueryParameter) {

    // Get team sessions (Session entities)
    List<ConsultantSessionResponseDTO> teamSessions =
        sessionService.getTeamSessionsForConsultant(consultant);

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

    var rcAuthToken = rocketChatCredentials.getRocketChatToken();

    // Enrich sessions and chats
    List<ConsultantSessionResponseDTO> enrichedSessions = emptyList();
    List<ConsultantSessionResponseDTO> enrichedChats = emptyList();

    if (isNotEmpty(sessions)) {
      enrichedSessions = updateConsultantSessionValues(sessions, rcAuthToken, consultant);
    }

    if (isNotEmpty(chats)) {
      enrichedChats = updateConsultantChatValues(chats, rcAuthToken, consultant);
    }

    // MATRIX MIGRATION: Merge sessions and chats by groupId
    // For group chats, we have BOTH a Session and a Chat entity with the same groupId
    // We need to combine them into a single ConsultantSessionResponseDTO
    var chatsByGroupId =
        enrichedChats.stream()
            .filter(chat -> chat.getChat() != null && chat.getChat().getGroupId() != null)
            .collect(Collectors.toMap(chat -> chat.getChat().getGroupId(), chat -> chat));

    // Add sessions, merging with matching chats
    for (ConsultantSessionResponseDTO session : enrichedSessions) {
      if (session.getSession() != null && session.getSession().getGroupId() != null) {
        var matchingChat = chatsByGroupId.get(session.getSession().getGroupId());
        if (matchingChat != null) {
          // Merge: session already has session data, add chat data from matching chat
          session.setChat(matchingChat.getChat());
          chatsByGroupId.remove(session.getSession().getGroupId()); // Mark as merged
        }
      }
      allSessions.add(session);
    }

    // Add remaining chats that didn't match any session (old-style chats without sessions)
    allSessions.addAll(chatsByGroupId.values());

    return allSessions;
  }

  private void sortSessionsByLastMessageDateDesc(List<ConsultantSessionResponseDTO> sessions) {
    sessions.sort(Comparator.comparing(ConsultantSessionResponseDTO::getLatestMessage).reversed());
  }

  private List<ConsultantSessionResponseDTO> updateConsultantSessionValues(
      List<ConsultantSessionResponseDTO> sessions, String rcAuthToken, Consultant consultant) {
    return this.consultantSessionEnricher.updateRequiredConsultantSessionValues(
        sessions, rcAuthToken, consultant);
  }

  private List<ConsultantSessionResponseDTO> updateConsultantChatValues(
      List<ConsultantSessionResponseDTO> chats, String rcAuthToken, Consultant consultant) {
    return this.consultantChatEnricher.updateRequiredConsultantChatValues(
        chats, rcAuthToken, consultant);
  }
}
