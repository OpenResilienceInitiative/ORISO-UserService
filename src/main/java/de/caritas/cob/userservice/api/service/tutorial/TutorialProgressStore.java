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
 * constraint serialize writes to one scope. A user-scoped advisory lock additionally keeps the
 * per-user row cap atomic when replicas create different scopes concurrently.
 */
@Component
@RequiredArgsConstructor
public class TutorialProgressStore {

  private static final int USER_WRITE_LOCK_TIMEOUT_SECONDS = 5;

  private final @NonNull TutorialProgressRepository tutorialProgressRepository;
  private final @NonNull PlatformTransactionManager transactionManager;

  public TutorialProgress upsert(TutorialProgress desired, int maxRowsPerUser) {
    return inNewTransaction(() -> writeExistingOrWithUserLock(desired, maxRowsPerUser));
  }

  private TutorialProgress writeExistingOrWithUserLock(
      TutorialProgress desired, int maxRowsPerUser) {
    if (findScope(desired) != null) {
      return upsertAndReload(desired);
    }
    return writeNewWithUserLock(desired, maxRowsPerUser);
  }

  private TutorialProgress writeNewWithUserLock(TutorialProgress desired, int maxRowsPerUser) {
    var lockAcquired =
        tutorialProgressRepository.acquireUserProgressLock(
            desired.getUserId(), USER_WRITE_LOCK_TIMEOUT_SECONDS);
    if (!Integer.valueOf(1).equals(lockAcquired)) {
      throw new IllegalStateException("tutorial progress user write lock timed out");
    }
    Throwable writeFailure = null;
    try {
      return writeNewOrConcurrentExisting(desired, maxRowsPerUser);
    } catch (RuntimeException | Error failure) {
      writeFailure = failure;
      throw failure;
    } finally {
      releaseUserWriteLock(desired.getUserId(), writeFailure);
    }
  }

  private TutorialProgress writeNewOrConcurrentExisting(
      TutorialProgress desired, int maxRowsPerUser) {
    var existing = findScope(desired);
    if (existing == null
        && tutorialProgressRepository.countByUserId(desired.getUserId()) >= maxRowsPerUser) {
      throw new BadRequestException("tutorial progress row limit reached for this user");
    }
    return upsertAndReload(desired);
  }

  private TutorialProgress upsertAndReload(TutorialProgress desired) {
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

  private void releaseUserWriteLock(String userId, Throwable writeFailure) {
    Integer lockReleased;
    try {
      lockReleased = tutorialProgressRepository.releaseUserProgressLock(userId);
    } catch (RuntimeException | Error releaseFailure) {
      handleReleaseFailure(releaseFailure, writeFailure);
      return;
    }
    if (!Integer.valueOf(1).equals(lockReleased)) {
      handleReleaseFailure(
          new IllegalStateException("tutorial progress user write lock was not released"),
          writeFailure);
    }
  }

  private void handleReleaseFailure(Throwable releaseFailure, Throwable writeFailure) {
    if (writeFailure != null) {
      writeFailure.addSuppressed(releaseFailure);
      return;
    }
    if (releaseFailure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    throw (Error) releaseFailure;
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
