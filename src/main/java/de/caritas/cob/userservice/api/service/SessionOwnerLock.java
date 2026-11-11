package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a Matrix write for a case owner only while that counsellor still owns the case, under the
 * session row lock every ownership change takes. A takeover that commits in between cannot slip
 * past the check, so a former owner is never handed room power after losing the case. Shared by the
 * after-commit owner power raise and its persistent retry.
 */
final class SessionOwnerLock {

  private SessionOwnerLock() {}

  /**
   * @return true when nothing was left to do (the counsellor no longer owns the case) or {@code
   *     action} succeeded; false when {@code action} failed
   */
  static boolean runWhileStillOwner(
      PlatformTransactionManager transactionManager,
      SessionRepository sessionRepository,
      Long sessionId,
      String ownerConsultantId,
      BooleanSupplier action) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              var session = sessionRepository.findByIdForUpdate(sessionId);
              boolean stillOwner =
                  session.isPresent()
                      && session.get().getConsultant() != null
                      && Objects.equals(session.get().getConsultant().getId(), ownerConsultantId);
              return !stillOwner || action.getAsBoolean();
            }));
  }
}
