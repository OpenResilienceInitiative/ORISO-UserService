package de.caritas.cob.userservice.api.service.sessionlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.matrix.MatrixRoomMembershipProvider;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserSessionListServiceTest {

  private static final String USER_ID = "user-id";

  @InjectMocks private UserSessionListService userSessionListService;
  @Mock private SessionService sessionService;
  @Mock private ChatService chatService;
  @Mock private MatrixRoomMembershipProvider matrixRoomMembershipProvider;
  @Mock private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(userSessionListService, "featureTopicsEnabled", false);
    userSessionListService.setSessionTopicEnrichmentService(sessionTopicEnrichmentService);
    lenient()
        .when(matrixRoomMembershipProvider.joinedRoomsForAccount(USER_ID))
        .thenReturn(Set.of());
  }

  @Test
  void mergesDatabaseSessionsAndChatsWithMatrixMembership() {
    var session = sessionResponse("!session:matrix.example", 1_700_000_000L);
    var chatStart = LocalDateTime.of(2026, 7, 26, 10, 15);
    var chat = chatResponse(1L, "!chat:matrix.example", ConversationType.INTERNAL_GROUP, chatStart);
    when(sessionService.getSessionsForUserId(USER_ID)).thenReturn(List.of(session));
    when(chatService.getChatsForUserId(USER_ID)).thenReturn(List.of(chat));
    when(matrixRoomMembershipProvider.joinedRoomsForAccount(USER_ID))
        .thenReturn(Set.of("!chat:matrix.example"));

    var result = userSessionListService.retrieveSessionsForAuthenticatedUser(USER_ID);

    assertThat(result).containsExactly(session, chat);
    assertThat(session.getSession().getMessagesRead()).isTrue();
    assertThat(session.getLatestMessage()).hasTime(1_700_000_000_000L);
    assertThat(chat.getChat().isMessagesRead()).isTrue();
    assertThat(chat.getChat().isSubscribed()).isTrue();
    assertThat(chat.getLatestMessage()).isEqualTo(Timestamp.valueOf(chatStart));
  }

  @Test
  void marksChatAsNotSubscribedWhenUserDidNotJoinTheMatrixRoom() {
    var chat =
        chatResponse(
            1L, "!not-joined:matrix.example", ConversationType.INTERNAL_GROUP, LocalDateTime.now());
    when(chatService.getChatsForUserId(USER_ID)).thenReturn(List.of(chat));

    userSessionListService.retrieveSessionsForAuthenticatedUser(USER_ID);

    assertThat(chat.getChat().isSubscribed()).isFalse();
  }

  @Test
  void enrichesOnlySessionEntriesWithTopicsWhenEnabled() {
    ReflectionTestUtils.setField(userSessionListService, "featureTopicsEnabled", true);
    var session = sessionResponse("!session:matrix.example", 1_700_000_000L);
    var chat =
        chatResponse(
            1L, "!chat:matrix.example", ConversationType.INTERNAL_GROUP, LocalDateTime.now());
    when(sessionService.getSessionsForUserId(USER_ID)).thenReturn(List.of(session));
    when(chatService.getChatsForUserId(USER_ID)).thenReturn(List.of(chat));

    userSessionListService.retrieveSessionsForAuthenticatedUser(USER_ID);

    verify(sessionTopicEnrichmentService).enrichSessionWithTopicData(session.getSession());
  }

  @Test
  void knownChatRoomDoesNotTriggerASecondSessionLookup() {
    var chat =
        chatResponse(
            1L, "!chat:matrix.example", ConversationType.INTERNAL_GROUP, LocalDateTime.now());
    when(chatService.getChatSessionsByGroupIds(Set.of("!chat:matrix.example")))
        .thenReturn(List.of(chat));
    when(chatService.getChatsForUserId(USER_ID)).thenReturn(List.of(chat));

    var result =
        userSessionListService.retrieveSessionsForAuthenticatedUserAndGroupIds(
            USER_ID, List.of("!chat:matrix.example"), Set.of("user"));

    assertThat(result).containsExactly(chat);
    verify(sessionService, never())
        .getSessionsByUserAndGroupIds(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anySet(),
            org.mockito.ArgumentMatchers.anySet());
  }

  @Test
  void unassignedInternalChatIsNotExposedByRoomOrChatId() {
    var chat =
        chatResponse(
            1087L,
            "!internal:matrix.example",
            ConversationType.INTERNAL_GROUP,
            LocalDateTime.now());
    when(chatService.getChatSessionsByGroupIds(Set.of("!internal:matrix.example")))
        .thenReturn(List.of(chat));
    when(chatService.getChatSessionsByIds(Set.of(1087L))).thenReturn(List.of(chat));
    when(chatService.getChatsForUserId(USER_ID)).thenReturn(List.of());

    assertThat(
            userSessionListService.retrieveSessionsForAuthenticatedUserAndGroupIds(
                USER_ID, List.of("!internal:matrix.example"), Set.of("user")))
        .isEmpty();
    assertThat(userSessionListService.retrieveChatsForUserAndChatIds(USER_ID, List.of(1087L)))
        .isEmpty();
  }

  @Test
  void publicSelfHelpChatCanBeResolvedByDeepLinkWithoutAssignment() {
    var chat =
        chatResponse(
            1088L, "!self-help:matrix.example", ConversationType.SELF_HELP, LocalDateTime.now());
    when(chatService.getChatSessionsByIds(Set.of(1088L))).thenReturn(List.of(chat));
    when(chatService.getChatsForUserId(USER_ID)).thenReturn(List.of());

    assertThat(userSessionListService.retrieveChatsForUserAndChatIds(USER_ID, List.of(1088L)))
        .containsExactly(chat);
  }

  private UserSessionResponseDTO sessionResponse(String roomId, long messageDate) {
    return new UserSessionResponseDTO()
        .session(new SessionDTO().groupId(roomId).messageDate(messageDate).messagesRead(false));
  }

  private UserSessionResponseDTO chatResponse(
      long id, String roomId, ConversationType conversationType, LocalDateTime start) {
    var chat = new UserChatDTO();
    chat.setId(id);
    chat.setGroupId(roomId);
    chat.setConversationType(conversationType);
    chat.setStartDateWithTime(start);
    chat.setMessagesRead(false);
    return new UserSessionResponseDTO().chat(chat);
  }
}
