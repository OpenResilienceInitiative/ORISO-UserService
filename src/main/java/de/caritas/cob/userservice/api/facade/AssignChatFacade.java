package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Facade for capsuling to assign a user to a chat. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignChatFacade {

  private final ChatService chatService;
  private final UserService userService;

  /**
   * Assign a chat to the authenticatedUser. <br>
   * The chat is resolved first by RocketChat/Matrix groupId, then falls back to numeric database
   * chat ID. This dual lookup supports the invite-link flow where the frontend may supply either a
   * RocketChat group ID string or the numeric database ID (e.g. 102559).
   *
   * <p>In this assignment process is no further validation, because everyone is allowed to be added
   * to this chat.
   *
   * @param groupId the rocket chat group id or numeric chat database id
   * @param authenticatedUser that authenticated user
   */
  public void assignChat(String groupId, AuthenticatedUser authenticatedUser) {
    Chat chat = getChat(groupId);
    User user = getUser(authenticatedUser);

    chatService.saveUserChatRelation(UserChat.builder().user(user).chat(chat).build());
  }

  /**
   * Resolves a chat by its RocketChat/Matrix group ID. If not found and the provided string is a
   * valid long, falls back to a database primary-key lookup. This handles the invite-link flow
   * where the frontend sometimes passes the numeric chat ID instead of a group ID.
   */
  private Chat getChat(String groupId) {
    Optional<Chat> chatByGroupId = chatService.getChatByGroupId(groupId);
    if (chatByGroupId.isPresent()) {
      return chatByGroupId.get();
    }

    // Fallback: treat the groupId as a numeric database chat ID (used by the invite-link flow)
    if (groupId != null && groupId.matches("\\d+")) {
      try {
        long chatId = Long.parseLong(groupId);
        Optional<Chat> chatById = chatService.getChat(chatId);
        if (chatById.isPresent()) {
          log.debug(
              "AssignChatFacade: resolved chat by numeric id {} (groupId lookup missed)", chatId);
          return chatById.get();
        }
      } catch (NumberFormatException ignored) {
        // Not a valid long, fall through to not-found
      }
    }

    throw new NotFoundException("Chat with group id %s not found", groupId);
  }

  private User getUser(AuthenticatedUser authenticatedUser) {
    return userService
        .getUserViaAuthenticatedUser(authenticatedUser)
        .orElseThrow(
            () ->
                new NotFoundException("User with id %s not found", authenticatedUser.getUserId()));
  }
}
