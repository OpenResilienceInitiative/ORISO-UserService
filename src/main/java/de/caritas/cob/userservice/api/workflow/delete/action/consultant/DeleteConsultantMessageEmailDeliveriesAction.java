package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Removes consultant-addressed delivery evidence before the account is hard-deleted. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteConsultantMessageEmailDeliveriesAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {
  private final @NonNull ReplyEmailDeliveryRepository repository;

  @Override
  public void execute(ConsultantDeletionWorkflowDTO target) {
    try {
      repository.deleteByRecipientKindAndRecipientUserId(
          RecipientKind.CONSULTANT, target.getConsultant().getId());
    } catch (Exception failure) {
      log.error("UserService delete workflow error: ", failure);
      target
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(CONSULTANT)
                  .deletionTargetType(DeletionTargetType.USER_CONTENT)
                  .identifier(target.getConsultant().getId())
                  .reason("Could not delete consultant message-email delivery records")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
