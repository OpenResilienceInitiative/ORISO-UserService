package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes all {@link EventNotification}s addressed to an asker.
 *
 * <p>Notification rows carry the recipient's identity, session references, third-party names and a
 * per-notification read timestamp, and previously outlived the account indefinitely (KDG epic
 * #1010, task 2b).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteAskerEventNotificationsAction
    implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull EventNotificationRepository eventNotificationRepository;

  /**
   * Deletes the whole notification feed of the {@link User} being deleted.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} containing the user
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    try {
      this.eventNotificationRepository.deleteByRecipientUserId(actionTarget.getUser().getUserId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(ASKER)
                  .deletionTargetType(DeletionTargetType.DATABASE)
                  .identifier(actionTarget.getUser().getUserId())
                  .reason("Could not delete event notifications")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
