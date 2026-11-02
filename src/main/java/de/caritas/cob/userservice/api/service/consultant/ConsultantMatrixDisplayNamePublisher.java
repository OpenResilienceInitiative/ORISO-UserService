package de.caritas.cob.userservice.api.service.consultant;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes a counsellor's Matrix {@code displayname} once the surrounding transaction has committed
 * (ORISO-UserService#1541).
 *
 * <p>Same mechanism as the PUT path in {@code
 * ConsultantUpdateService#scheduleMatrixDisplayNameUpdate}: the name comes from {@link
 * ConsultantDisplayNameResolver} (ADR-002 §2, never the real name) and is resolved <em>now</em>,
 * while the entity carries the new state; only the outbound call waits for the commit, so a
 * rolled-back update leaves Synapse untouched. Without an active transaction the push happens
 * immediately. Failures are logged and never fail the update.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultantMatrixDisplayNamePublisher {

  private final @NonNull MatrixUserClient matrixUserClient;
  private final @NonNull ConsultantDisplayNameResolver consultantDisplayNameResolver;

  /**
   * @param consultant the counsellor carrying the new state; ignored without a Matrix account
   */
  public void publishAfterCommit(Consultant consultant) {
    if (consultant == null || consultant.getMatrixUserId() == null) {
      return;
    }
    final String consultantId = consultant.getId();
    final String matrixUserId = consultant.getMatrixUserId();
    final String displayName = consultantDisplayNameResolver.resolveMatrixDisplayName(consultant);

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      push(consultantId, matrixUserId, displayName);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            push(consultantId, matrixUserId, displayName);
          }
        });
  }

  private void push(String consultantId, String matrixUserId, String displayName) {
    try {
      // A false answer is Synapse refusing, not a transport error; it must be visible too.
      if (!matrixUserClient.updateUserDisplayName(matrixUserId, displayName)) {
        log.warn(
            "Matrix did not accept the display name update for consultant {}; the stored update"
                + " stands and the profile is re-sent on the next display name change",
            consultantId);
      }
    } catch (Exception e) {
      log.warn(
          "Matrix display name update failed for consultant {}, but continuing", consultantId, e);
    }
  }
}
