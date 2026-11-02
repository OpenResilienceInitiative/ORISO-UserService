package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.CONSULTANT;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.service.statistics.ConsultantMessageStatService;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Deletes the {@link ConsultantMessageStat} rows of a consultant. They are keyed by a pseudonym of
 * the consultant id and used only for the consultant's own statistics tab, so they have no purpose
 * once the account is gone; before this action they stayed behind indefinitely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteConsultantMessageStatsAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull ConsultantMessageStatService consultantMessageStatService;

  /**
   * Deletes the message counts of the {@link Consultant} being deleted.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} containing the consultant
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    try {
      this.consultantMessageStatService.deleteForConsultant(actionTarget.getConsultant().getId());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(CONSULTANT)
                  .deletionTargetType(DeletionTargetType.USER_CONTENT)
                  .identifier(actionTarget.getConsultant().getId())
                  .reason("Could not delete message statistics")
                  .timestamp(nowInUtc())
                  .build());
    }
  }
}
