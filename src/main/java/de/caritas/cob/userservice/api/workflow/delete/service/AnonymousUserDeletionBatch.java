package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs one anonymous-user deletion batch.
 *
 * <p>This class holds no transaction. Selection and each individual deletion own theirs, so neither
 * a single poisoned user nor the notification that follows the batch can roll back work that
 * already succeeded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnonymousUserDeletionBatch {

  private final @NonNull AnonymousUserDeletionCandidates deletionCandidates;
  private final @NonNull AnonymousUserDeletionUnit deletionUnit;

  /** Deletes anonymous users whose sessions are done and overdue. */
  public List<DeletionWorkflowError> deleteOverdueUsers() {
    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();

    for (String userId : deletionCandidates.findOverdueAnonymousUserIds()) {
      workflowErrors.addAll(deleteIsolated(userId));
    }

    return workflowErrors;
  }

  /**
   * Keeps one user's failure from ending the batch.
   *
   * <p>The deletion commits when {@link AnonymousUserDeletionUnit#deleteUser(String)} returns, so a
   * failed commit surfaces here rather than inside the workflow actions. Recording it as a workflow
   * error lets the remaining users proceed and still reports the failure.
   */
  private List<DeletionWorkflowError> deleteIsolated(String userId) {
    try {
      return deletionUnit.deleteUser(userId);
    } catch (RuntimeException exception) {
      log.error(
          "Anonymous deletion failed for one user and was isolated from the rest of the batch. "
              + "Failure type: {}",
          exception.getClass().getSimpleName());
      return List.of(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(userId)
              .reason("Unable to delete user")
              .timestamp(nowInUtc())
              .build());
    }
  }
}
