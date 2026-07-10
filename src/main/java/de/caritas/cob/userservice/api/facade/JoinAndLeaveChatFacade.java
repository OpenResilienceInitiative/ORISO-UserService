package de.caritas.cob.userservice.api.facade;

import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.actions.chat.ChatReCreator;
import de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveUserFromGroupException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ChatPermissionVerifier;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.chat.GroupChatRoleService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade for capsuling to join a chat. */
@Service
@RequiredArgsConstructor
public class JoinAndLeaveChatFacade {

  private final ChatService chatService;
  private final ChatPermissionVerifier chatPermissionVerifier;
  private final ConsultantService consultantService;
  private final UserService userService;
  private final RocketChatService rocketChatService;
  private final ChatReCreator chatReCreator;
  private final GroupChatMembershipService groupChatMembershipService;
  private final MatrixChatShutdownService matrixChatShutdownService;
  private final GroupChatRoleService groupChatRoleService;

  /**
   * Join a chat.
   *
   * @param chatId the chat id
   * @param authenticatedUser the authenticated user
   */
  public void joinChat(Long chatId, AuthenticatedUser authenticatedUser) {
    Chat chat = getChat(chatId);
    this.chatPermissionVerifier.verifyPermissionForChat(chat);

    if (!isBlank(groupChatMembershipService.resolveMatrixRoomId(chat))) {
      String matrixUserId = retrieveMatrixUserId(authenticatedUser);
      if (isBlank(matrixUserId)
          || !groupChatMembershipService.addMemberToRoom(chat, matrixUserId)) {
        throw new InternalServerErrorException(
            String.format(
                "User with id %s could not join the Matrix group chat.",
                authenticatedUser.getUserId()));
      }
      return;
    }

    String rcUserId = retrieveRcUserId(authenticatedUser);
    if (isBlank(rcUserId)) {
      throw new InternalServerErrorException(
          String.format("User with id %s has no Rocket.Chat-ID.", authenticatedUser.getUserId()));
    }

    try {
      rocketChatService.addUserToGroup(rcUserId, chat.getGroupId());
    } catch (RocketChatAddUserToGroupException e) {
      throw new InternalServerErrorException(e.getMessage(), LogService::logRocketChatError);
    }
  }

  public void verifyCanModerate(Long chatId) {
    Chat chat = getChat(chatId);
    this.chatPermissionVerifier.verifyCanModerateChat(chat);
  }

  /**
   * Leave a chat.
   *
   * <p>The "was this the last member?" decision is answered from the Matrix room state via {@link
   * GroupChatMembershipService}, NOT from Rocket.Chat: with Rocket.Chat disabled (the default,
   * ADR-004) its member query always returns an empty list, which made every single leave delete
   * the whole chat for everyone.
   *
   * @param chatId the id of the chat
   * @param authenticatedUser the authenticated user
   */
  public void leaveChat(Long chatId, AuthenticatedUser authenticatedUser) {
    Chat chat = getChat(chatId);
    this.chatPermissionVerifier.verifyPermissionForChat(chat);

    Optional<Consultant> leavingConsultant =
        consultantService.getConsultantViaAuthenticatedUser(authenticatedUser);
    Optional<User> leavingUser =
        leavingConsultant.isPresent()
            ? Optional.empty()
            : userService.getUserViaAuthenticatedUser(authenticatedUser);
    String leavingMatrixUserId =
        leavingConsultant
            .map(Consultant::getMatrixUserId)
            .or(() -> leavingUser.map(User::getMatrixUserId))
            .orElse(null);
    boolean matrixChat = !isBlank(groupChatMembershipService.resolveMatrixRoomId(chat));

    if (matrixChat) {
      if (isBlank(leavingMatrixUserId)) {
        throw new InternalServerErrorException(
            String.format("User with id %s has no Matrix user ID.", authenticatedUser.getUserId()));
      }
      if (leavingConsultant.isPresent()) {
        groupChatRoleService.leaveSeries(chat, leavingConsultant.get());
      } else {
        groupChatMembershipService.removeLeavingMemberFromRoom(chat, leavingMatrixUserId);
        leavingUser.ifPresent(leaver -> chatService.deleteUserChatRelation(chat, leaver));
      }
    } else {
      String rcUserId =
          leavingConsultant
              .map(Consultant::getRocketChatId)
              .or(() -> leavingUser.map(User::getRcUserId))
              .orElse(null);
      if (isBlank(rcUserId)) {
        throw new InternalServerErrorException(
            String.format("User with id %s has no Rocket.Chat-ID.", authenticatedUser.getUserId()));
      }
      try {
        rocketChatService.removeUserFromGroup(rcUserId, chat.getGroupId());
      } catch (RocketChatRemoveUserFromGroupException e) {
        throw new InternalServerErrorException(e.getMessage(), LogService::logInternalServerError);
      }

      groupChatMembershipService.removeLeavingMemberFromRoom(chat, leavingMatrixUserId);
      leavingUser.ifPresent(leaver -> chatService.deleteUserChatRelation(chat, leaver));
    }

    if (groupChatMembershipService.hasRemainingHumanMembers(chat, leavingMatrixUserId)) {
      return;
    }

    if (!matrixChat) {
      deleteMessengerChat(chat.getGroupId());
    }
    if (chat.isRepetitive()) {
      var rcGroupId = chatReCreator.recreateMessengerChat(chat);
      chatReCreator.updateAsNextChat(chat, rcGroupId);
    } else {
      chatService.deleteChat(chat);
      matrixChatShutdownService.shutdownRoom(chat);
    }
  }

  private void deleteMessengerChat(String groupId) {
    if (!rocketChatService.deleteGroupAsSystemUser(groupId)) {
      var message = String.format("Could not delete Rocket.Chat group with id %s", groupId);
      throw new InternalServerErrorException(message);
    }
  }

  private Chat getChat(Long chatId) {
    Chat chat =
        chatService
            .getChat(chatId)
            .orElseThrow(() -> new NotFoundException("Chat with id %s not found", chatId));

    if (isFalse(chat.isActive())) {
      throw new ConflictException(
          String.format(
              "User could not join/leave Chat with id %s, because it's not started.",
              chat.getId()));
    }

    return chat;
  }

  private String retrieveRcUserId(AuthenticatedUser authenticatedUser) {
    final AtomicReference<String> rcUserId = new AtomicReference<>();
    consultantService
        .getConsultantViaAuthenticatedUser(authenticatedUser)
        .ifPresentOrElse(
            consultant -> rcUserId.set(consultant.getRocketChatId()),
            () ->
                userService
                    .getUserViaAuthenticatedUser(authenticatedUser)
                    .ifPresent(user -> rcUserId.set(user.getRcUserId())));

    return rcUserId.get();
  }

  private String retrieveMatrixUserId(AuthenticatedUser authenticatedUser) {
    final AtomicReference<String> matrixUserId = new AtomicReference<>();
    consultantService
        .getConsultantViaAuthenticatedUser(authenticatedUser)
        .ifPresentOrElse(
            consultant -> matrixUserId.set(consultant.getMatrixUserId()),
            () ->
                userService
                    .getUserViaAuthenticatedUser(authenticatedUser)
                    .ifPresent(user -> matrixUserId.set(user.getMatrixUserId())));

    return matrixUserId.get();
  }
}
