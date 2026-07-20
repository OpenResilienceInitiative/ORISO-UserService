package de.caritas.cob.userservice.api.facade;

import static java.util.Objects.isNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.chat.GroupChatPermissionService;
import de.caritas.cob.userservice.api.service.notification.GroupChatLifecycleNotificationService;
import de.caritas.cob.userservice.api.service.notification.GroupChatNotificationRecipientService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade for capsuling starting a chat. */
@Service
@RequiredArgsConstructor
public class StartChatFacade {

  private final @NonNull ChatService chatService;
  private final @NonNull RocketChatService rocketChatService;
  private final @NonNull GroupChatPermissionService groupChatPermissionService;
  private final @NonNull GroupChatNotificationRecipientService notificationRecipientService;
  private final @NonNull GroupChatLifecycleNotificationService
      groupChatLifecycleNotificationService;

  /**
   * Starts the given {@link Chat}.
   *
   * @param chat the {@link Chat} to be started
   * @param consultant the {@link Consultant}
   */
  public void startChat(Chat chat, Consultant consultant) {

    groupChatPermissionService.requireCanModerate(chat, consultant);
    checkIfChatIsAlreadyActive(chat);
    checkChatGroup(chat);

    try {
      if (!isMatrixChat(chat)) {
        rocketChatService.addUserToGroup(consultant.getRocketChatId(), chat.getGroupId());
      }
      chat.setActive(true);
      chatService.saveChat(chat);
      try {
        publishOpenedNotification(chat);
      } catch (RuntimeException notificationException) {
        LogService.logInternalServerError(notificationException);
      }
    } catch (RocketChatAddUserToGroupException e) {
      throw new InternalServerErrorException(e.getMessage(), LogService::logRocketChatError);
    }
  }

  private void checkIfChatIsAlreadyActive(Chat chat) {
    if (isTrue(chat.isActive())) {
      throw new ConflictException(
          String.format("Chat with id %s is already started.", chat.getId()));
    }
  }

  private void checkChatGroup(Chat chat) {
    if (isNull(chat.getGroupId()) && !isMatrixChat(chat)) {
      throw new InternalServerErrorException(
          String.format("Chat with id %s has no Rocket.Chat group id", chat.getId()));
    }
  }

  private boolean isMatrixChat(Chat chat) {
    return MatrixIds.isRoomId(chat.getGroupId())
        || (isNull(chat.getGroupId()) && MatrixIds.isRoomId(chat.getMatrixRoomId()));
  }

  private void publishOpenedNotification(Chat chat) {
    if (!isMatrixChat(chat) || chat.getId() == null) {
      return;
    }
    groupChatLifecycleNotificationService.createOpenedNotifications(
        chat.getId(),
        chat.getCurrentOccurrenceIndex(),
        chat.getStartDate(),
        chat.getMatrixRoomId() != null ? chat.getMatrixRoomId() : chat.getGroupId(),
        null,
        chat.getChatModality() == Chat.ChatModality.VIDEO,
        notificationRecipientService.resolveRecipientIds(chat));
  }
}
