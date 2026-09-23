package de.caritas.cob.userservice.api.workflow.delete.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deletes the accounts of people who joined a self-help group without an account (FE#1499).
 *
 * <p>Their browser holds the only login; the password was minted client-side and never shown. Once
 * the longest possible login has ended, nobody can use the account again, so it goes through the
 * ordinary asker deletion (Keycloak, Matrix, group memberships, database). The group itself and its
 * room stay: only the person's own sessions are purged. Each account is deleted in a transaction of
 * its own, so one failure cannot roll back the others.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteTemporaryAccountsService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull AnonymousUserDeletionUnit deletionUnit;
  private final @NonNull WorkflowErrorMailService workflowErrorMailService;

  @Value("${user.temporary.deleteWorkflow.maxAge}")
  private Duration maxAge;

  /** Deletes every temporary account older than the configured maximum age. */
  public void deleteExpiredTemporaryAccounts() {
    var createdBefore = LocalDateTime.now().minus(maxAge);
    List<DeletionWorkflowError> workflowErrors = new ArrayList<>();
    for (String userId : userRepository.findTemporaryAccountIdsCreatedBefore(createdBefore)) {
      workflowErrors.addAll(deleteIsolated(userId));
    }
    if (!workflowErrors.isEmpty()) {
      notifyAbout(workflowErrors);
    }
  }

  private List<DeletionWorkflowError> deleteIsolated(String userId) {
    try {
      return deletionUnit.deleteUser(userId);
    } catch (RuntimeException exception) {
      log.error(
          "Temporary account deletion failed for one user and was isolated from the rest. "
              + "Failure type: {}",
          exception.getClass().getSimpleName());
      return List.of(
          DeletionWorkflowError.builder()
              .deletionSourceType(ASKER)
              .deletionTargetType(DeletionTargetType.DATABASE)
              .identifier(userId)
              .reason("Unable to delete temporary account")
              .timestamp(nowInUtc())
              .build());
    }
  }

  private void notifyAbout(List<DeletionWorkflowError> workflowErrors) {
    try {
      workflowErrorMailService.buildAndSendErrorMail(workflowErrors);
    } catch (RuntimeException exception) {
      log.error(
          "Deletion workflow error notification failed; completed deletions are retained. "
              + "Failure type: {}",
          exception.getClass().getSimpleName());
    }
  }
}
