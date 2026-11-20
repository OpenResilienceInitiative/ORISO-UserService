package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.actions.chat.StopChatActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.service.chat.GroupChatPermissionService;
import de.caritas.cob.userservice.api.service.chat.RecurringGroupStopCoordinator;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * Facade to encapsulate the steps to stop a running chat session.
 */
@Service
@RequiredArgsConstructor
public class StopChatFacade {

  private final @NonNull GroupChatPermissionService groupChatPermissionService;
  private final @NonNull ActionsRegistry actionsRegistry;
  private final @NonNull RecurringGroupStopCoordinator recurringStops;

  /**
   * Stops the given {@link Chat} and resets or deletes it depending on if it's repetitive or not.
   *
   * @param chat {@link Chat}
   * @param consultant {@link Consultant}
   */
  // One transaction: if the Matrix room cannot be shut down, the chat's deletion rolls back and
  // the counsellor gets an error instead of a stopped chat whose room is still open (#1513).
  @Transactional
  public void stopChat(Chat chat, Consultant consultant) {
    groupChatPermissionService.requireCanModerate(chat, consultant);
    if (RecurringGroupStopCoordinator.applies(chat)) {
      recurringStops.stop(chat, consultant, null);
      return;
    }

    this.actionsRegistry
        .buildContainerForType(Chat.class)
        .addActionToExecute(StopChatActionCommand.class)
        .executeActions(chat);
  }
}
