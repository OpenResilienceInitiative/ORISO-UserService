package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Removes reply-email delivery evidence when the recipient account is hard-deleted. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteAskerReplyEmailDeliveriesAction
    implements ActionCommand<AskerDeletionWorkflowDTO> {
  private final @NonNull ReplyEmailDeliveryRepository repository;

  @Override
  public void execute(AskerDeletionWorkflowDTO target) {
    try {
      repository.deleteByRecipientUserId(target.getUser().getUserId());
    } catch (Exception failure) {
      log.error("UserService delete workflow error: ", failure);
      target
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(ASKER)
                  .deletionTargetType(DeletionTargetType.USER_CONTENT)
                  .identifier(target.getUser().getUserId())
                  .reason("Could not delete reply-email delivery records")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
