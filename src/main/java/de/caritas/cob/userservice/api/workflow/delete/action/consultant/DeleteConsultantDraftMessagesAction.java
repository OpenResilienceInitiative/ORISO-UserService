package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes all {@link DraftMessage}s of a consultant.
 *
 * <p>Drafts are the only place counselling content is stored unencrypted server-side, so they must
 * not survive account deletion (KDG epic #1010, task 5a).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteConsultantDraftMessagesAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull DraftMessageRepository draftMessageRepository;

  /**
   * Deletes all {@link DraftMessage}s belonging to the {@link Consultant} being deleted.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} containing the consultant
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    try {
      this.draftMessageRepository.deleteByUserId(actionTarget.getConsultant().getId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(CONSULTANT)
                  .deletionTargetType(DeletionTargetType.DATABASE)
                  .identifier(actionTarget.getConsultant().getId())
                  .reason("Could not delete draft messages")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
