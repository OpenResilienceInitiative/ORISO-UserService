package de.caritas.cob.userservice.api.workflow.delete.action;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.List;

/**
 * Tells the account-row actions whether the unencrypted user content of the account was cleared.
 *
 * <p>Drafts and the notification feed are keyed by the user and hold counselling content that is
 * not end-to-end encrypted. Once the account row is gone, nothing points at those rows any more, so
 * a failed cleanup would leave them behind permanently. The account row is what the next scheduler
 * run needs to retry, so it has to survive a failed cleanup (#983, KDG epic #1010).
 */
public final class UserContentCleanup {

  private UserContentCleanup() {}

  /**
   * @param workflowErrors the errors collected by the deletion workflow so far
   * @return {@code true} if clearing the account's unencrypted content failed
   */
  public static boolean failed(List<DeletionWorkflowError> workflowErrors) {
    return workflowErrors.stream()
        .anyMatch(error -> DeletionTargetType.USER_CONTENT == error.getDeletionTargetType());
  }
}
