package de.caritas.cob.userservice.api.facade;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.chat.ChatReCreator;
import de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.chat.GroupChatRoleService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JoinAndLeaveChatFacadeTest {

  private static final long CHAT_ID = 42L;
  private static final String MATRIX_ROOM_ID = "!group:matrix.oriso.org";
  private static final String MATRIX_USER_ID = "@member:matrix.oriso.org";

  @InjectMocks private JoinAndLeaveChatFacade facade;

  @Mock private ChatService chatService;
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ChatPermissionVerifier chatPermissionVerifier;
  @Mock private ConsultantService consultantService;
  @Mock private UserService userService;
  @Mock private ChatReCreator chatReCreator;
  @Mock private GroupChatMembershipService groupChatMembershipService;
  @Mock private MatrixChatShutdownService matrixChatShutdownService;
  @Mock private GroupChatRoleService groupChatRoleService;

  @Test
  void joinChatShouldRejectUnknownChat() {
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> facade.joinChat(CHAT_ID, authenticatedUser));
  }

  @Test
  void joinChatShouldRejectInactiveChat() {
    Chat chat = mock(Chat.class);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));

    assertThrows(ConflictException.class, () -> facade.joinChat(CHAT_ID, authenticatedUser));
  }

  @Test
  void joinChatShouldEnforceChatPermission() {
    Chat chat = activeChatWithoutRoom();
    doThrow(new ForbiddenException("forbidden"))
        .when(chatPermissionVerifier)
        .verifyPermissionForChat(chat);

    assertThrows(ForbiddenException.class, () -> facade.joinChat(CHAT_ID, authenticatedUser));
  }

  @Test
  void joinChatShouldRequireMatrixRoom() {
    Chat chat = activeChatWithoutRoom();
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(null);

    assertThrows(
        InternalServerErrorException.class, () -> facade.joinChat(CHAT_ID, authenticatedUser));
  }

  @Test
  void joinChatShouldRequireMatrixIdentity() {
    Chat chat = activeMatrixChat();
    Consultant consultant = mock(Consultant.class);
    when(consultantService.getConsultantViaAuthenticatedUser(authenticatedUser))
        .thenReturn(Optional.of(consultant));

    assertThrows(
        InternalServerErrorException.class, () -> facade.joinChat(CHAT_ID, authenticatedUser));
  }

  @Test
  void joinChatShouldAddMatrixUserToRoom() {
    Chat chat = activeMatrixChat();
    User user = matrixUser();
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(groupChatMembershipService.addMemberToRoom(chat, MATRIX_USER_ID)).thenReturn(true);

    facade.joinChat(CHAT_ID, authenticatedUser);

    verify(groupChatMembershipService).addMemberToRoom(chat, MATRIX_USER_ID);
  }

  @Test
  void joinChatShouldFailWhenMatrixMembershipCannotBeAdded() {
    Chat chat = activeMatrixChat();
    User user = matrixUser();
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));

    assertThrows(
        InternalServerErrorException.class, () -> facade.joinChat(CHAT_ID, authenticatedUser));

    verify(groupChatMembershipService).addMemberToRoom(chat, MATRIX_USER_ID);
  }

  @Test
  void leaveChatShouldRequireMatrixIdentity() {
    activeMatrixChat();

    assertThrows(
        InternalServerErrorException.class, () -> facade.leaveChat(CHAT_ID, authenticatedUser));
  }

  @Test
  void consultantLeaveShouldUseSeriesRoleService() {
    Chat chat = activeMatrixChat();
    Consultant consultant = mock(Consultant.class);
    when(consultant.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    when(consultantService.getConsultantViaAuthenticatedUser(authenticatedUser))
        .thenReturn(Optional.of(consultant));
    when(groupChatMembershipService.hasRemainingHumanMembers(chat, MATRIX_USER_ID))
        .thenReturn(true);

    facade.leaveChat(CHAT_ID, authenticatedUser);

    verify(groupChatRoleService).leaveSeries(chat, consultant);
    verify(matrixChatShutdownService, never()).shutdownRoom(chat);
  }

  @Test
  void askerLeaveShouldRemoveMatrixMembershipAndDatabaseRelation() {
    Chat chat = activeMatrixChat();
    User user = matrixUser();
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(groupChatMembershipService.hasRemainingHumanMembers(chat, MATRIX_USER_ID))
        .thenReturn(true);

    facade.leaveChat(CHAT_ID, authenticatedUser);

    verify(groupChatMembershipService).removeLeavingMemberFromRoom(chat, MATRIX_USER_ID);
    verify(chatService).deleteUserChatRelation(chat, user);
  }

  @Test
  void lastMemberLeavingSingleChatShouldDeleteChatAndShutdownMatrixRoom() {
    Chat chat = activeMatrixChat();
    User user = matrixUser();
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));

    facade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatService).deleteChat(chat);
    verify(matrixChatShutdownService).shutdownRoom(chat);
  }

  @Test
  void lastMemberLeavingRepetitiveChatShouldCreateNextOccurrence() {
    Chat chat = activeMatrixChat();
    User user = matrixUser();
    LocalDateTime nextStart = LocalDateTime.parse("2026-08-04T10:00:00");
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(chat.isRepetitive()).thenReturn(true);
    when(chat.nextStart()).thenReturn(nextStart);
    when(chatReCreator.recreateMessengerChat(chat)).thenReturn("!next:matrix.oriso.org");

    facade.leaveChat(CHAT_ID, authenticatedUser);

    verify(chatReCreator).updateAsNextChat(chat, "!next:matrix.oriso.org");
    verify(chatService, never()).deleteChat(chat);
  }

  @Test
  void lastMemberLeavingFinalOccurrenceShouldCompleteSeries() {
    Chat chat = activeMatrixChat();
    User user = matrixUser();
    when(userService.getUserViaAuthenticatedUser(authenticatedUser)).thenReturn(Optional.of(user));
    when(chat.isRepetitive()).thenReturn(true);
    when(chat.nextStart()).thenReturn(null);

    facade.leaveChat(CHAT_ID, authenticatedUser);

    verify(matrixChatShutdownService).shutdownRoom(chat);
    verify(chat).setActive(false);
    verify(chatService).saveChat(chat);
  }

  private Chat activeChat() {
    Chat chat = activeChatWithoutRoom();
    when(groupChatMembershipService.resolveMatrixRoomId(chat)).thenReturn(MATRIX_ROOM_ID);
    return chat;
  }

  private Chat activeMatrixChat() {
    return activeChat();
  }

  private Chat activeChatWithoutRoom() {
    Chat chat = mock(Chat.class);
    when(chat.isActive()).thenReturn(true);
    when(chatService.getChat(CHAT_ID)).thenReturn(Optional.of(chat));
    return chat;
  }

  private User matrixUser() {
    User user = mock(User.class);
    when(user.getMatrixUserId()).thenReturn(MATRIX_USER_ID);
    return user;
  }
}
