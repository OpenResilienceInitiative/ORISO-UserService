package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade for capsuling to assign a user to a chat. */
@Service
@RequiredArgsConstructor
public class AssignChatFacade {

  private final ChatService chatService;
  private final UserService userService;

  /**
   * Assign a chat to the authenticated user.
   *
   * <p>No additional validation is required because everyone is allowed to join this chat.
   *
   * @param matrixRoomId Matrix room ID
   * @param authenticatedUser authenticated user
   */
  public void assignChat(String matrixRoomId, AuthenticatedUser authenticatedUser) {
    Chat chat = getChat(matrixRoomId);
    assignChat(chat, authenticatedUser);
  }

  /** Assigns a V2 chat resolved by its stable numeric Series id. */
  public void assignChat(Long chatId, AuthenticatedUser authenticatedUser) {
    Chat chat =
        chatService
            .getChat(chatId)
            .orElseThrow(() -> new NotFoundException("Chat with id %s not found", chatId));
    assignChat(chat, authenticatedUser);
  }

  private void assignChat(Chat chat, AuthenticatedUser authenticatedUser) {
    User user = getUser(authenticatedUser);

    chatService.saveUserChatRelation(UserChat.builder().user(user).chat(chat).build());
  }

  private Chat getChat(String matrixRoomId) {
    return chatService
        .getChatByMatrixRoomId(matrixRoomId)
        .orElseThrow(
            () -> new NotFoundException("Chat with Matrix room ID %s not found", matrixRoomId));
  }

  private User getUser(AuthenticatedUser authenticatedUser) {
    return userService
        .getUserViaAuthenticatedUser(authenticatedUser)
        .orElseThrow(
            () ->
                new NotFoundException("User with id %s not found", authenticatedUser.getUserId()));
  }
}
