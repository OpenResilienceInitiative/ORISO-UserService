package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.MATRIX_ROOM_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignChatFacadeTest {

  private AssignChatFacade assignChatFacade;

  @Mock private ChatService chatService;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private UserService userService;

  @Mock
  private de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer
      appointmentEvents;

  @Mock private de.caritas.cob.userservice.api.port.out.UserChatRepository userChats;

  @org.junit.jupiter.api.BeforeEach
  void wireRealGroupPolicy() {
    var groupPolicy =
        de.caritas.cob.userservice.api.testHelper.PermittingDpaOwnerFixture.groupPolicy();
    assignChatFacade =
        new AssignChatFacade(chatService, userService, userChats, groupPolicy, appointmentEvents);
  }

  @Test
  void assignChat_Should_ThrowNotFoundException_WhenChatDoesNotExist() {
    when(chatService.getChatByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.empty());

    NotFoundException exception =
        assertThrows(
            NotFoundException.class,
            () -> assignChatFacade.assignChat(MATRIX_ROOM_ID, authenticatedUser));

    verify(chatService).getChatByMatrixRoomId(MATRIX_ROOM_ID);
    assertThat(exception.getMessage())
        .isEqualTo(String.format("Chat with Matrix room ID %s not found", MATRIX_ROOM_ID));
  }

  @Test
  void assignChat_Should_ThrowNotFoundException_WhenUserDoesNotExist() {
    when(chatService.getChatByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(authenticatedUser.getUserId()).thenReturn(USER_ID);
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.empty());

    NotFoundException exception =
        assertThrows(
            NotFoundException.class,
            () -> assignChatFacade.assignChat(MATRIX_ROOM_ID, authenticatedUser));

    verify(userService).getUserViaAuthenticatedUser(authenticatedUser);
    assertThat(exception.getMessage())
        .isEqualTo(String.format("User with id %s not found", USER_ID));
  }

  @Test
  void assignChat_Should_AddUserToChat() {
    var legacyChat = legacyGroup().repetitive(true).matrixRoomId(MATRIX_ROOM_ID).build();
    when(chatService.getChatByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(legacyChat));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(USER));

    assignChatFacade.assignChat(MATRIX_ROOM_ID, authenticatedUser);

    verify(chatService)
        .saveUserChatRelation(UserChat.builder().user(USER).chat(legacyChat).build());
  }

  @Test
  void assignChatBySeriesId_Should_AddUserToChat_When_InviteTokenMatches() {
    var owner = new de.caritas.cob.userservice.api.model.Consultant();
    owner.setTenantId(41L);
    var selfHelpGroup =
        Chat.builder()
            .id(ACTIVE_CHAT.getId())
            .topic("group")
            .initialStartDate(ACTIVE_CHAT.getStartDate())
            .startDate(ACTIVE_CHAT.getStartDate())
            .chatOwner(owner)
            .conversationType(ConversationType.SELF_HELP)
            .inviteToken("link-token")
            .build();
    when(chatService.getChat(selfHelpGroup.getId())).thenReturn(Optional.of(selfHelpGroup));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(USER));

    assignChatFacade.assignChat(selfHelpGroup.getId(), "link-token", authenticatedUser);

    verify(chatService)
        .saveUserChatRelation(UserChat.builder().user(USER).chat(selfHelpGroup).build());
  }

  @ParameterizedTest
  @MethodSource("legacyRepeatingSelfHelpGroups")
  void assignChatBySeriesId_Should_AcceptLegacyRepeatingSelfHelpGroup(Chat legacySelfHelpGroup) {
    when(chatService.getChat(legacySelfHelpGroup.getId()))
        .thenReturn(Optional.of(legacySelfHelpGroup));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(USER));

    assignChatFacade.assignChat(legacySelfHelpGroup.getId(), "link-token", authenticatedUser);

    verify(chatService)
        .saveUserChatRelation(UserChat.builder().user(USER).chat(legacySelfHelpGroup).build());
  }

  private static Stream<Chat> legacyRepeatingSelfHelpGroups() {
    return Stream.of(
        legacyGroup().repetitive(true).build(),
        legacyGroup().repeatCount(2).build(),
        legacyGroup().chatInterval(ChatInterval.WEEKLY).build());
  }

  private static Chat.ChatBuilder legacyGroup() {
    var owner = new de.caritas.cob.userservice.api.model.Consultant();
    owner.setTenantId(41L);
    return Chat.builder()
        .id(ACTIVE_CHAT.getId())
        .topic("group")
        .initialStartDate(ACTIVE_CHAT.getStartDate())
        .startDate(ACTIVE_CHAT.getStartDate())
        .chatOwner(owner)
        .inviteToken("link-token");
  }

  @Test
  void assignChatBySeriesId_Should_RejectWrongInviteTokenWithoutAssigning() {
    var selfHelpGroup = chatWithToken(ConversationType.SELF_HELP, "valid-token");
    when(chatService.getChat(selfHelpGroup.getId())).thenReturn(Optional.of(selfHelpGroup));

    assertThrows(
        ForbiddenException.class,
        () -> assignChatFacade.assignChat(selfHelpGroup.getId(), "wrong-token", authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  @Test
  void assignChatBySeriesId_Should_RejectMissingInviteTokenWithoutAssigning() {
    var selfHelpGroup = chatWithToken(ConversationType.SELF_HELP, "valid-token");
    when(chatService.getChat(selfHelpGroup.getId())).thenReturn(Optional.of(selfHelpGroup));

    assertThrows(
        ForbiddenException.class,
        () -> assignChatFacade.assignChat(selfHelpGroup.getId(), null, authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  @Test
  void assignChatBySeriesId_Should_RejectChatWithoutInviteToken() {
    var selfHelpGroup = chatWithToken(ConversationType.SELF_HELP, null);
    when(chatService.getChat(selfHelpGroup.getId())).thenReturn(Optional.of(selfHelpGroup));

    assertThrows(
        ForbiddenException.class,
        () -> assignChatFacade.assignChat(selfHelpGroup.getId(), "token", authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  @Test
  void assignChatBySeriesId_Should_RejectNonSelfHelpChatEvenWithMatchingToken() {
    var internalGroup = chatWithToken(ConversationType.INTERNAL_GROUP, "link-token");
    when(chatService.getChat(internalGroup.getId())).thenReturn(Optional.of(internalGroup));

    assertThrows(
        ForbiddenException.class,
        () -> assignChatFacade.assignChat(internalGroup.getId(), "link-token", authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  @ParameterizedTest
  @MethodSource("legacyRepeatingSelfHelpGroups")
  void assignChatBySeriesId_Should_RejectLegacyRepeatingGroupWithWrongToken(Chat legacyGroup) {
    when(chatService.getChat(legacyGroup.getId())).thenReturn(Optional.of(legacyGroup));

    assertThrows(
        ForbiddenException.class,
        () -> assignChatFacade.assignChat(legacyGroup.getId(), "wrong-token", authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  @Test
  void assignChatBySeriesId_Should_RejectLegacyOneOffGroupEvenWithMatchingToken() {
    // Without conversation_type a one-off legacy row is an internal group, not a self-help circle.
    var legacyOneOffGroup = legacyGroup().repeatCount(1).build();
    when(chatService.getChat(legacyOneOffGroup.getId())).thenReturn(Optional.of(legacyOneOffGroup));

    assertThrows(
        ForbiddenException.class,
        () ->
            assignChatFacade.assignChat(
                legacyOneOffGroup.getId(), "link-token", authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  @Test
  void assignChatBySeriesId_Should_ThrowNotFound_When_SeriesDoesNotExist() {
    when(chatService.getChat(ACTIVE_CHAT.getId())).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () -> assignChatFacade.assignChat(ACTIVE_CHAT.getId(), "link-token", authenticatedUser));

    verify(chatService, never()).saveUserChatRelation(any());
  }

  private Chat chatWithToken(ConversationType conversationType, String inviteToken) {
    return Chat.builder()
        .id(ACTIVE_CHAT.getId())
        .topic("group")
        .initialStartDate(ACTIVE_CHAT.getStartDate())
        .startDate(ACTIVE_CHAT.getStartDate())
        .conversationType(conversationType)
        .inviteToken(inviteToken)
        .build();
  }
}
