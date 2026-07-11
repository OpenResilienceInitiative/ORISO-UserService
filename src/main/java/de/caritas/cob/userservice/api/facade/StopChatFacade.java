package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.chat.GroupChatPermissionService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * Facade to encapsulate the steps to stop a running chat session.
 */
@Service
@RequiredArgsConstructor
public class StopChatFacade {

  private final @NonNull GroupChatPermissionService groupChatPermissionService;
  private final @NonNull ActionsRegistry actionsRegistry;

  /**
   * Stops the given {@link Chat} and resets or deletes it depending on if it's repetitive or not.
   *
   * @param chat {@link Chat}
   * @param consultant {@link Consultant}
   */
  public void stopChat(Chat chat, Consultant consultant) {
    groupChatPermissionService.requireCanModerate(chat, consultant);

    this.actionsRegistry
        .buildContainerForType(Chat.class)
        .addActionToExecute(StopChatActionCommand.class)
        .executeActions(chat);
  }
}
