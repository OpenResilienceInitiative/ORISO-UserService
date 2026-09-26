package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.RecipientRole;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatInviteTokens;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer;
import de.caritas.cob.userservice.api.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Facade for capsuling to assign a user to a chat. */
@Service
@RequiredArgsConstructor
public class AssignChatFacade {

  private final ChatService chatService;
  private final UserService userService;
  private final GroupAppointmentSeriesEventProducer appointmentEvents;

  /**
   * Assign a chat to the authenticated user.
   *
   * <p>No additional validation is required because everyone is allowed to join this chat.
   *
   * @param matrixRoomId Matrix room ID
   * @param authenticatedUser authenticated user
   */
  @Transactional
  public void assignChat(String matrixRoomId, AuthenticatedUser authenticatedUser) {
    Chat chat = getChat(matrixRoomId);
    assignChat(chat, authenticatedUser);
  }

  /**
   * Assigns a V2 chat resolved by its stable numeric Series id — the invite link. The number is
   * guessable, so the link's secret token must match, and only self-help groups are open to clients
   * (#1237). The link may come from any Träger.
   */
  @Transactional
  public void assignChat(Long chatId, String inviteToken, AuthenticatedUser authenticatedUser) {
    Chat chat =
        chatService
            .getChat(chatId)
            .orElseThrow(() -> new NotFoundException("Chat with id %s not found", chatId));
    if (chat.getConversationType() != ConversationType.SELF_HELP) {
      throw new ForbiddenException("Only self-help groups can be joined through an invite link");
    }
    if (!GroupChatInviteTokens.matches(chat.getInviteToken(), inviteToken)) {
      throw new ForbiddenException("The invite link for chat %s is not valid", chatId);
    }
    assignChat(chat, authenticatedUser);
  }

  private void assignChat(Chat chat, AuthenticatedUser authenticatedUser) {
    User user = getUser(authenticatedUser);

    chatService.saveUserChatRelation(UserChat.builder().user(user).chat(chat).build());
    appointmentEvents.recordMemberJoined(chat, RecipientRole.PARTICIPANT, user.getUserId());
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
