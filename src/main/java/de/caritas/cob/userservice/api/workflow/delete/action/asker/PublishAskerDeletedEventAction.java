package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Announces the deletion of a {@link User} to add-ons (lifecycle event {@code user.deleted},
 * ADR-034). Runs last in the asker deletion workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishAskerDeletedEventAction implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull UserRepository userRepository;
  private final @NonNull LifecycleEventPublisher lifecycleEventPublisher;

  /**
   * Publishes {@code user.deleted} once the account row is gone. A row kept because an earlier step
   * failed is retried by the next scheduler run, which publishes then.
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} with the deleted {@link User}
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    var user = actionTarget.getUser();
    if (user == null || user.getUserId() == null) {
      return;
    }
    if (userRepository.findById(user.getUserId()).isPresent()) {
      log.info("Asker account row was kept, user.deleted is published on the retry");
      return;
    }
    lifecycleEventPublisher.userDeleted(
        user.getTenantId(), user.getUserId(), LifecycleEventPublisher.ROLE_ASKER);
  }
}
