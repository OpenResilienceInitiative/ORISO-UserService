package de.caritas.cob.userservice.api.service.tutorial;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.TutorialProgress;
import de.caritas.cob.userservice.api.port.out.TutorialProgressRepository;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the transactional upsert invariant for one versioned tutorial-progress scope.
 *
 * <p>Two replicas may both observe a missing row. MariaDB's native upsert and the database unique
 * constraint serialize both writes without leaking or logging a duplicate-key failure through the
 * HTTP interface.
 */
@Component
@RequiredArgsConstructor
public class TutorialProgressStore {

  private final @NonNull TutorialProgressRepository tutorialProgressRepository;
  private final @NonNull PlatformTransactionManager transactionManager;

  public TutorialProgress upsert(TutorialProgress desired, int maxRowsPerUser) {
    return inNewTransaction(() -> writeOnce(desired, maxRowsPerUser));
  }

  private TutorialProgress writeOnce(TutorialProgress desired, int maxRowsPerUser) {
    var existing = findScope(desired);
    if (existing == null
        && tutorialProgressRepository.countByUserId(desired.getUserId()) >= maxRowsPerUser) {
      throw new BadRequestException("tutorial progress row limit reached for this user");
    }
    tutorialProgressRepository.upsertScopedProgress(
        desired.getUserId(),
        desired.getSurface(),
        desired.getTourId(),
        desired.getTourVersion(),
        desired.getStatus(),
        desired.getCurrentStepId(),
        desired.getStartedAt(),
        desired.getCompletedAt(),
        desired.getCreateDate(),
        desired.getUpdateDate(),
        desired.getTenantId());
    return tutorialProgressRepository
        .findByUserIdAndSurfaceAndTourIdAndTourVersion(
            desired.getUserId(),
            desired.getSurface(),
            desired.getTourId(),
            desired.getTourVersion())
        .orElseThrow(
            () -> new IllegalStateException("atomic tutorial progress upsert lost its row"));
  }

  private TutorialProgress findScope(TutorialProgress desired) {
    return tutorialProgressRepository
        .findByUserIdAndSurfaceAndTourIdAndTourVersion(
            desired.getUserId(),
            desired.getSurface(),
            desired.getTourId(),
            desired.getTourVersion())
        .orElse(null);
  }

  private <T> T inNewTransaction(Supplier<T> action) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    // A no-op ON DUPLICATE KEY UPDATE does not make the winner's row part of this transaction's
    // REPEATABLE_READ snapshot. READ_COMMITTED guarantees the canonical read immediately after
    // the atomic statement can see the row committed by another replica.
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return Objects.requireNonNull(transaction.execute(ignored -> action.get()));
  }
}
