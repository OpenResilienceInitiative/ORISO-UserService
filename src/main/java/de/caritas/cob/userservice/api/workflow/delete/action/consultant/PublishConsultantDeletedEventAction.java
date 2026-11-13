package de.caritas.cob.userservice.api.workflow.delete.action.consultant;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Announces the deletion of a {@link Consultant} to add-ons (lifecycle event {@code user.deleted},
 * ADR-032). Runs last in the consultant deletion workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishConsultantDeletedEventAction
    implements ActionCommand<ConsultantDeletionWorkflowDTO> {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull LifecycleEventPublisher lifecycleEventPublisher;

  /**
   * Publishes {@code user.deleted} once the account row is gone. A row kept because an earlier step
   * failed is retried by the next scheduler run, which publishes then.
   *
   * @param actionTarget the {@link ConsultantDeletionWorkflowDTO} with the deleted {@link
   *     Consultant}
   */
  @Override
  public void execute(ConsultantDeletionWorkflowDTO actionTarget) {
    var consultant = actionTarget.getConsultant();
    if (consultant == null || consultant.getId() == null) {
      return;
    }
    // findById, not a query: it is not narrowed by the tenant filter.
    if (consultantRepository.findById(consultant.getId()).isPresent()) {
      log.info("Consultant account row was kept, user.deleted is published on the retry");
      return;
    }
    lifecycleEventPublisher.userDeleted(
        consultant.getTenantId(), consultant.getId(), LifecycleEventPublisher.ROLE_CONSULTANT);
  }
}
