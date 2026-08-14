package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.DraftMessage;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes all {@link DraftMessage}s of an asker.
 *
 * <p>Drafts are the only place counselling content is stored unencrypted server-side, so they must
 * not survive account deletion (KDG epic #1010, task 5a).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteAskerDraftMessagesAction implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull DraftMessageRepository draftMessageRepository;

  /**
   * Deletes all {@link DraftMessage}s belonging to the {@link User} being deleted.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} containing the user
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    try {
      this.draftMessageRepository.deleteByUserId(actionTarget.getUser().getUserId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(ASKER)
                  .deletionTargetType(DeletionTargetType.DATABASE)
                  .identifier(actionTarget.getUser().getUserId())
                  .reason("Could not delete draft messages")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
