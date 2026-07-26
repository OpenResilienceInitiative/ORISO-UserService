package de.caritas.cob.userservice.api.service.sessionlist;

import de.caritas.cob.userservice.api.adapters.web.dto.UserChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.MatrixRoomMembershipProvider;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds asker session lists from database state and actual Matrix room membership.
 *
 * <p>Encrypted message previews and read state are owned by the frontend Matrix client.
 */
@RequiredArgsConstructor
@Service
public class UserSessionListService {

  private final @NonNull SessionService sessionService;
  private final @NonNull ChatService chatService;
  private final @NonNull MatrixRoomMembershipProvider matrixRoomMembershipProvider;

  @Value("${feature.topics.enabled}")
  private boolean featureTopicsEnabled;

  @Autowired(required = false)
  private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  public List<UserSessionResponseDTO> retrieveSessionsForAuthenticatedUser(String userId) {
    var sessions = sessionService.getSessionsForUserId(userId);
    var chats = chatService.getChatsForUserId(userId);

    var mergedSessions = mergeUserSessionsAndChats(userId, sessions, chats);
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

  public List<UserSessionResponseDTO> retrieveSessionsForAuthenticatedUserAndGroupIds(
      String userId, List<String> roomIds, Set<String> roles) {
    var groupIds = new HashSet<>(roomIds);
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

    return mergeUserSessionsAndChats(userId, sessions, chats);
  }

  public List<UserSessionResponseDTO> retrieveSessionsForAuthenticatedUserAndSessionIds(
      String userId, List<Long> sessionIds, Set<String> roles) {
    var uniqueSessionIds = new HashSet<>(sessionIds);
    var sessions = sessionService.getSessionsByUserAndSessionIds(userId, uniqueSessionIds, roles);
    var groupIds =
        sessions.stream()
            .map(sessionResponse -> sessionResponse.getSession().getGroupId())
            .collect(Collectors.toSet());
    var chats = chatService.getChatSessionsByGroupIds(groupIds);
    return mergeUserSessionsAndChats(userId, sessions, chats);
  }

  public List<UserSessionResponseDTO> retrieveChatsForUserAndChatIds(
      String userId, List<Long> chatIds) {
    var uniqueChatIds = new HashSet<>(chatIds);
    var chats = filterChatsVisibleToUser(userId, chatService.getChatSessionsByIds(uniqueChatIds));
    return updateUserChatValues(chats, matrixRoomMembershipProvider.joinedRoomsForAccount(userId));
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
      String userId, List<UserSessionResponseDTO> sessions, List<UserSessionResponseDTO> chats) {
    var joinedRoomIds = matrixRoomMembershipProvider.joinedRoomsForAccount(userId);
    var allSessions = new ArrayList<UserSessionResponseDTO>();
    allSessions.addAll(updateUserSessionValues(sessions));
    allSessions.addAll(updateUserChatValues(chats, joinedRoomIds));
    return allSessions;
  }

  private List<UserSessionResponseDTO> updateUserSessionValues(
      List<UserSessionResponseDTO> sessions) {
    sessions.forEach(
        sessionResponse -> {
          var session = sessionResponse.getSession();
          session.setMessagesRead(true);
          if (sessionResponse.getLatestMessage() == null
              && session.getMessageDate() != null
              && session.getMessageDate() > 0) {
            sessionResponse.setLatestMessage(new Date(session.getMessageDate() * 1000));
          }
        });
    return sessions;
  }

  private List<UserSessionResponseDTO> updateUserChatValues(
      List<UserSessionResponseDTO> chats, Set<String> joinedRoomIds) {
    chats.forEach(
        sessionResponse -> {
          var chat = sessionResponse.getChat();
          chat.setSubscribed(joinedRoomIds.contains(chat.getGroupId()));
          chat.setMessagesRead(true);
          if (sessionResponse.getLatestMessage() == null && chat.getStartDateWithTime() != null) {
            sessionResponse.setLatestMessage(Timestamp.valueOf(chat.getStartDateWithTime()));
          }
        });
    return chats;
  }

  void setSessionTopicEnrichmentService(
      SessionTopicEnrichmentService sessionTopicEnrichmentService) {
    this.sessionTopicEnrichmentService = sessionTopicEnrichmentService;
  }
}
