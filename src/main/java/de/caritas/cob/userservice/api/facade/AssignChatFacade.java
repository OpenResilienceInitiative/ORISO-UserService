package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.GROUP_INVITE_INVALID;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.RecipientRole;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.chat.GroupChatInviteTokens;
import de.caritas.cob.userservice.api.service.chat.GroupCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer;
import de.caritas.cob.userservice.api.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Facade for capsuling to assign a user to a chat. */
@Service
@RequiredArgsConstructor
public class AssignChatFacade {

  private final ChatService chatService;
  private final UserService userService;
  private final UserChatRepository userChats;
  private final GroupCounsellingDpaPolicy groupCounsellingDpaPolicy;
  private final GroupAppointmentSeriesEventProducer appointmentEvents;
  private final MembersOnlyChatAccess membersOnlyChatAccess;

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
    // Each refusal of the link names GROUP_INVITE_INVALID with its old status, so the app can say
    // the link does not work (#1483). The Matrix-room-id path above is no invite link.
    Chat chat = chatService.getChat(chatId).orElseThrow(() -> invalidInvite(HttpStatus.NOT_FOUND));
    if (ChatConverter.conversationTypeOf(chat) != ConversationType.SELF_HELP
        || !GroupChatInviteTokens.matches(chat.getInviteToken(), inviteToken)
        || membersOnlyChatAccess.concerns(chat)) {
      throw invalidInvite(HttpStatus.FORBIDDEN);
    }
    assignChat(chat, authenticatedUser);
  }

  private static CustomValidationHttpStatusException invalidInvite(HttpStatus status) {
    return new CustomValidationHttpStatusException(GROUP_INVITE_INVALID, status);
  }

  private void assignChat(Chat chat, AuthenticatedUser authenticatedUser) {
    // A members-only chat is a room of selected colleagues; no advice seeker is ever one of them.
    if (membersOnlyChatAccess.concerns(chat)) {
      throw new ForbiddenException("Chat %s cannot be joined", chat.getId());
    }
    User user = getUser(authenticatedUser);
    if (userChats.findByChatAndUser(chat, user).isPresent()) {
      throw new ConflictException("User is already assigned to chat");
    }
    groupCounsellingDpaPolicy.requireAuthorizedEnrolment(chat, user.getMatrixUserId());
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
