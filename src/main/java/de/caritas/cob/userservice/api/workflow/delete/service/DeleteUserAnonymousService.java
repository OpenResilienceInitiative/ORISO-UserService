package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of anonymous users. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteUserAnonymousService {

  private final @NonNull AnonymousUserDeletionBatch deletionBatch;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;

  /** Deletes all anonymous users with special constraints. */
  public void deleteInactiveAnonymousUsers() {
    List<DeletionWorkflowError> workflowErrors = deletionBatch.deleteOverdueUsers();

    if (isNotEmpty(workflowErrors)) {
      try {
        this.workflowErrorMailService.buildAndSendErrorMail(workflowErrors);
      } catch (RuntimeException exception) {
        log.error(
            "Deletion workflow error notification failed; completed deletion results are retained. "
                + "Failure type: {}",
            exception.getClass().getSimpleName());
      }
    }
  }
}
