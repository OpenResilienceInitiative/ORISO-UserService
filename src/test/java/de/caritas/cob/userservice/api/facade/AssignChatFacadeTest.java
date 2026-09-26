package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.ACTIVE_CHAT;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.MATRIX_ROOM_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignChatFacadeTest {

  @InjectMocks private AssignChatFacade assignChatFacade;

  @Mock private ChatService chatService;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private UserService userService;

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
    when(chatService.getChatByMatrixRoomId(MATRIX_ROOM_ID)).thenReturn(Optional.of(ACTIVE_CHAT));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(USER));

    assignChatFacade.assignChat(MATRIX_ROOM_ID, authenticatedUser);

    verify(chatService)
        .saveUserChatRelation(UserChat.builder().user(USER).chat(ACTIVE_CHAT).build());
  }

  @Test
  void assignChatBySeriesId_Should_AddUserToChat_When_InviteTokenMatches() {
    var selfHelpGroup =
        Chat.builder()
            .id(ACTIVE_CHAT.getId())
            .topic("group")
            .initialStartDate(ACTIVE_CHAT.getStartDate())
            .startDate(ACTIVE_CHAT.getStartDate())
            .conversationType(ConversationType.SELF_HELP)
            .inviteToken("link-token")
            .build();
    when(chatService.getChat(selfHelpGroup.getId())).thenReturn(Optional.of(selfHelpGroup));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(USER));

    assignChatFacade.assignChat(selfHelpGroup.getId(), "link-token", authenticatedUser);

    verify(chatService)
        .saveUserChatRelation(UserChat.builder().user(USER).chat(selfHelpGroup).build());
  }

  @Test
  void assignChatBySeriesId_Should_AcceptLegacyRepeatingSelfHelpGroup() {
    var legacySelfHelpGroup =
        Chat.builder()
            .id(ACTIVE_CHAT.getId())
            .topic("group")
            .initialStartDate(ACTIVE_CHAT.getStartDate())
            .startDate(ACTIVE_CHAT.getStartDate())
            .repetitive(true)
            .inviteToken("link-token")
            .build();
    when(chatService.getChat(legacySelfHelpGroup.getId()))
        .thenReturn(Optional.of(legacySelfHelpGroup));
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(USER));

    assignChatFacade.assignChat(legacySelfHelpGroup.getId(), "link-token", authenticatedUser);

    verify(chatService)
        .saveUserChatRelation(UserChat.builder().user(USER).chat(legacySelfHelpGroup).build());
  }
}
