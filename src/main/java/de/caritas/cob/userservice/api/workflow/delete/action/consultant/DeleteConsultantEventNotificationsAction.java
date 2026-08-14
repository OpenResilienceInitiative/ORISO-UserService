package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EventNotification;
import de.caritas.cob.userservice.api.port.out.EventNotificationRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes all {@link EventNotification}s addressed to a consultant.
 *
 * <p>Notification rows carry the recipient's identity, session references, third-party names and a
 * per-notification read timestamp, and previously outlived the account indefinitely (KDG epic
 * #1010, task 2b).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteConsultantEventNotificationsAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull EventNotificationRepository eventNotificationRepository;

  /**
   * Deletes the whole notification feed of the {@link Consultant} being deleted.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} containing the consultant
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    try {
      this.eventNotificationRepository.deleteByRecipientUserId(
          actionTarget.getConsultant().getId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(CONSULTANT)
                  .deletionTargetType(DeletionTargetType.USER_CONTENT)
                  .identifier(actionTarget.getConsultant().getId())
                  .reason("Could not delete event notifications")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
