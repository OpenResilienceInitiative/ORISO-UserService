package de.caritas.cob.userservice.api.actions.chat;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isFalse;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Action to perform all necessary steps to stop an active group chat. */
@Component
@RequiredArgsConstructor
public class StopChatActionCommand implements ActionCommand<Chat> {

  private final ChatService chatService;
  private final ChatReCreator chatReCreator;
  private final MatrixChatShutdownService matrixChatShutdownService;

  /**
   * Deletes the given active chat and recreates it if repetitive. Repetitive chats get a fresh
   * Matrix room for their next occurrence and the old room is shut down (via {@link
   * ChatReCreator}); for non-repetitive chats the Matrix room is shut down directly. Either way the
   * shutdown is best-effort and removes all members so their clients can show the chat as stopped.
   *
   * @param chat the {@link Chat} to be stopped
   */
  @Override
  public void execute(Chat chat) {
    checkActiveState(chat);

    if (!MatrixIds.isRoomId(chat.getMatrixRoomId())) {
      throw new InternalServerErrorException(
          String.format("Chat with id %s has no Matrix room id", chat.getId()));
    }

    if (chat.isRepetitive() && chat.getChatInterval() == null) {
      throw new InternalServerErrorException(
          String.format("Finite chat series with id %s has no interval", chat.getId()));
    }

    if (chat.isRepetitive()) {
      if (nonNull(chat.nextStart())) {
        var matrixRoomId = chatReCreator.recreateMessengerChat(chat);
        chatReCreator.updateAsNextChat(chat, matrixRoomId);
      } else {
        matrixChatShutdownService.shutdownRoom(chat);
        chat.setActive(false);
        chatService.saveChat(chat);
      }
    } else {
      chatService.deleteChat(chat);
      matrixChatShutdownService.shutdownRoom(chat);
    }
  }

  private void checkActiveState(Chat chat) {
    if (isFalse(chat.isActive())) {
      throw new ConflictException(
          String.format("Chat with id %s is already stopped.", chat.getId()));
    }
  }
}
