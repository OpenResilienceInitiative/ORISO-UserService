package de.caritas.cob.userservice.api.service.sessionlist;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.container.RocketChatRoomInformation;
import de.caritas.cob.userservice.api.facade.sessionlist.RocketChatRoomInformationProvider;
import de.caritas.cob.userservice.api.helper.SessionListAnalyser;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class UserSessionListService {

  private final @NonNull SessionService sessionService;
  private final @NonNull ChatService chatService;
  private final @NonNull RocketChatRoomInformationProvider rocketChatRoomInformationProvider;
  private final @NonNull SessionListAnalyser sessionListAnalyser;

  @Value("${feature.topics.enabled}")
  private boolean featureTopicsEnabled;

  @Autowired(required = false)
  private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  /**
   * Returns a list of {@link UserSessionResponseDTO} for the specified user ID.
   *
   * @param userId Keycloak/MariaDB user ID
   * @param rocketChatCredentials the rocket chat credentials
   * @return {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> retrieveSessionsForAuthenticatedUser(
      String userId, RocketChatCredentials rocketChatCredentials) {

    List<UserSessionResponseDTO> sessions = sessionService.getSessionsForUserId(userId);
    List<UserSessionResponseDTO> chats = chatService.getChatsForUserId(userId);

    var mergedSessions = mergeUserSessionsAndChats(sessions, chats, rocketChatCredentials);
    if (featureTopicsEnabled) {
      enrichSessionsWithTopics(mergedSessions);
    }
    return mergedSessions;
  }

  private void enrichSessionsWithTopics(List<UserSessionResponseDTO> mergedSessions) {
    mergedSessions.stream()
        .map(UserSessionResponseDTO::getSession)
        .filter(java.util.Objects::nonNull)
        .forEach(sessionTopicEnrichmentService::enrichSessionWithTopicData);
  }

  /**
   * Returns a list of {@link UserSessionResponseDTO} for given user ID and rocket chat group IDs
   *
   * @param userId the ID of an user
   * @param rcGroupIds the rocket chat group IDs
   * @param rocketChatCredentials the credentials for accessing rocket chat
   * @param roles the roles of given user
   * @return {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> retrieveSessionsForAuthenticatedUserAndGroupIds(
      String userId,
      List<String> rcGroupIds,
      RocketChatCredentials rocketChatCredentials,
      Set<String> roles) {

    var groupIds = new HashSet<>(rcGroupIds);
    var chats = filterChatsVisibleToUser(userId, chatService.getChatSessionsByGroupIds(groupIds));
    var chatGroupIds =
        chats.stream()
            .map(UserSessionResponseDTO::getChat)
            .filter(java.util.Objects::nonNull)
            .map(UserChatDTO::getGroupId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    groupIds.removeAll(chatGroupIds);
    var sessions =
        groupIds.isEmpty()
            ? List.<UserSessionResponseDTO>of()
            : sessionService.getSessionsByUserAndGroupIds(userId, groupIds, roles);

    return mergeUserSessionsAndChats(sessions, chats, rocketChatCredentials);
  }

  /**
   * Returns a list of {@link UserSessionResponseDTO} for given user ID and session IDs.
   *
   * @param userId the ID of an user
   * @param sessionIds the session IDs
   * @param rocketChatCredentials the credentials for accessing rocket chat
   * @param roles the roles of given user
   * @return {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> retrieveSessionsForAuthenticatedUserAndSessionIds(
      String userId,
      List<Long> sessionIds,
      RocketChatCredentials rocketChatCredentials,
      Set<String> roles) {

    var uniqueSessionIds = new HashSet<>(sessionIds);
    var sessions = sessionService.getSessionsByUserAndSessionIds(userId, uniqueSessionIds, roles);
    var groupIds =
        sessions.stream()
            .map(sessionResponse -> sessionResponse.getSession().getGroupId())
            .collect(Collectors.toSet());
    var chats = chatService.getChatSessionsByGroupIds(groupIds);
    return mergeUserSessionsAndChats(sessions, chats, rocketChatCredentials);
  }

  public List<UserSessionResponseDTO> retrieveChatsForUserAndChatIds(
      String userId, List<Long> chatIds, RocketChatCredentials rocketChatCredentials) {
    var uniqueChatIds = new HashSet<>(chatIds);
    var chats = filterChatsVisibleToUser(userId, chatService.getChatSessionsByIds(uniqueChatIds));
    var rocketChatRoomInformation =
        rocketChatRoomInformationProvider.retrieveRocketChatInformation(rocketChatCredentials);
    return updateUserChatValues(
        chats, rocketChatRoomInformation, rocketChatCredentials.getRocketChatUserId());
  }

  private List<UserSessionResponseDTO> filterChatsVisibleToUser(
      String userId, List<UserSessionResponseDTO> candidates) {
    var assignedChatIds =
        chatService.getChatsForUserId(userId).stream()
            .map(UserSessionResponseDTO::getChat)
            .filter(java.util.Objects::nonNull)
            .map(UserChatDTO::getId)
            .collect(Collectors.toSet());

    return candidates.stream()
        .filter(UserSessionListService::hasChat)
        .filter(
            candidate ->
                candidate.getChat().getConversationType() == ConversationType.SELF_HELP
                    || assignedChatIds.contains(candidate.getChat().getId()))
        .toList();
  }

  private static boolean hasChat(UserSessionResponseDTO candidate) {
    return candidate != null && candidate.getChat() != null;
  }

  private List<UserSessionResponseDTO> mergeUserSessionsAndChats(
      List<UserSessionResponseDTO> sessions,
      List<UserSessionResponseDTO> chats,
      RocketChatCredentials rocketChatCredentials) {

    var rocketChatRoomInformation =
        rocketChatRoomInformationProvider.retrieveRocketChatInformation(rocketChatCredentials);

    List<UserSessionResponseDTO> allSessions = new ArrayList<>();
    allSessions.addAll(
        updateUserSessionValues(
            sessions, rocketChatRoomInformation, rocketChatCredentials.getRocketChatUserId()));
    allSessions.addAll(
        updateUserChatValues(
            chats, rocketChatRoomInformation, rocketChatCredentials.getRocketChatUserId()));

    return allSessions;
  }

  private List<UserSessionResponseDTO> updateUserSessionValues(
      List<UserSessionResponseDTO> sessions,
      RocketChatRoomInformation rocketChatRoomInformation,
      String rcUserId) {

    return sessions.stream()
        .map(
            sessionDTO ->
                updateRequiredUserSessionValues(rocketChatRoomInformation, rcUserId, sessionDTO))
        .collect(Collectors.toList());
  }

  private UserSessionResponseDTO updateRequiredUserSessionValues(
      RocketChatRoomInformation rocketChatRoomInformation,
      String rcUserId,
      UserSessionResponseDTO userSessionDTO) {

    SessionDTO session = userSessionDTO.getSession();
    String groupId = session.getGroupId();

    session.setMessagesRead(
        sessionListAnalyser.areMessagesForRocketChatGroupReadByUser(
            rocketChatRoomInformation.getReadMessages(), groupId));
    var messageUpdater = new AvailableLastMessageUpdater(this.sessionListAnalyser);
    messageUpdater.updateSessionWithAvailableLastMessage(
        userSessionDTO.getSession(),
        userSessionDTO::setLatestMessage,
        rocketChatRoomInformation,
        rcUserId);
    return userSessionDTO;
  }

  private List<UserSessionResponseDTO> updateUserChatValues(
      List<UserSessionResponseDTO> chats,
      RocketChatRoomInformation rocketChatRoomInformation,
      String rcUserId) {

    return chats.stream()
        .map(chat -> updateRequiredUserChatValues(rocketChatRoomInformation, rcUserId, chat))
        .collect(Collectors.toList());
  }

  private UserSessionResponseDTO updateRequiredUserChatValues(
      RocketChatRoomInformation rocketChatRoomInformation,
      String rcUserId,
      UserSessionResponseDTO sessionResponse) {
    UserChatDTO chat = sessionResponse.getChat();
    String groupId = chat.getGroupId();

    chat.setSubscribed(
        isRocketChatRoomSubscribedByUser(rocketChatRoomInformation.getUserRooms(), groupId));
    chat.setMessagesRead(
        sessionListAnalyser.areMessagesForRocketChatGroupReadByUser(
            rocketChatRoomInformation.getReadMessages(), groupId));
    updateUserChatValuesForAvailableLastMessage(
        rocketChatRoomInformation, rcUserId, sessionResponse, chat);
    return sessionResponse;
  }

  private void updateUserChatValuesForAvailableLastMessage(
      RocketChatRoomInformation rocketChatRoomInformation,
      String rcUserId,
      UserSessionResponseDTO sessionResponse,
      UserChatDTO chat) {
    new AvailableLastMessageUpdater(sessionListAnalyser)
        .updateChatWithAvailableLastMessage(
            chat, sessionResponse::setLatestMessage, rocketChatRoomInformation, rcUserId);
  }

  private boolean isRocketChatRoomSubscribedByUser(List<String> userRoomsList, String groupId) {
    return nonNull(userRoomsList) && userRoomsList.contains(groupId);
  }

  void setSessionTopicEnrichmentService(
      SessionTopicEnrichmentService sessionTopicEnrichmentService) {
    this.sessionTopicEnrichmentService = sessionTopicEnrichmentService;
  }
}
