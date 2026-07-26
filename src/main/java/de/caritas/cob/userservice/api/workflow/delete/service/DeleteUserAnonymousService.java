package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service to trigger deletion of anonymous users. */
@Service
@RequiredArgsConstructor
public class DeleteUserAnonymousService {

  private final @NonNull AnonymousUserDeletionBatch deletionBatch;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;

  /** Deletes all anonymous users with special constraints. */
  public void deleteInactiveAnonymousUsers() {
    List<DeletionWorkflowError> workflowErrors = deletionBatch.deleteOverdueUsers();

    if (isNotEmpty(workflowErrors)) {
      this.workflowErrorMailService.buildAndSendErrorMail(workflowErrors);
    }
  }
}
