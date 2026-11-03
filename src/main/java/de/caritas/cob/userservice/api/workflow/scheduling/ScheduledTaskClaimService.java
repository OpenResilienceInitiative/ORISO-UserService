package de.caritas.cob.userservice.api.workflow.scheduling;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Replica-neutral API for claiming one scheduled execution for a bounded interval. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledTaskClaimService {

  private static final int MAX_CLAIM_ATTEMPTS = 3;
  private final @NonNull ScheduledTaskClaimWriter claimWriter;

  public boolean tryClaim(String taskName, Duration claimDuration) {
    validate(taskName, claimDuration);
    return claimWithDeadlockRetry(
        taskName, () -> claimWriter.claim(taskName, claimDuration), false);
  }

  public Optional<ClaimLease> tryClaimLease(String taskName, Duration claimDuration) {
    validate(taskName, claimDuration);
    return claimWithDeadlockRetry(
        taskName,
        () ->
            claimWriter
                .claimUntil(taskName, claimDuration)
                .map(claimedUntil -> new ClaimLease(taskName, claimedUntil)),
        Optional.empty());
  }

  private <T> T claimWithDeadlockRetry(String taskName, Supplier<T> claim, T lostClaimResult) {
    for (int attempt = 1; ; attempt++) {
      try {
        return claim.get();
      } catch (DataAccessException claimConflict) {
        if (isMariaDbDeadlock(claimConflict) && attempt < MAX_CLAIM_ATTEMPTS) {
          continue;
        }
        if (claimWriter.hasActiveClaim(taskName)) {
          return lostClaimResult;
        }
        throw claimConflict;
      }
    }
  }

  private boolean isMariaDbDeadlock(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException && sqlException.getErrorCode() == 1213) {
        return true;
      }
    }
    return false;
  }

  /** Runs one bounded operation while the lease row remains exclusively locked. */
  public boolean runIfHeld(ClaimLease lease, Runnable operation) {
    return claimWriter.runIfHeld(lease, operation);
  }

  /**
   * Runs one sweep exclusively: takes a lease, runs the operation while the lease row is held and
   * releases the lease afterwards so the next tick of any replica can run again.
   *
   * <p>The operation runs in the lease's own transaction, so it commits before the lease is
   * released. A replica that loses the claim skips the run. The operation must still be idempotent:
   * the lease prevents overlap, not a later re-run.
   *
   * @return {@code true} if this replica ran the operation
   */
  public boolean runIfClaimed(String taskName, Duration claimDuration, Runnable operation) {
    var lease = tryClaimLease(taskName, claimDuration);
    if (lease.isEmpty()) {
      return false;
    }
    try {
      return runIfHeld(lease.get(), operation);
    } finally {
      try {
        release(lease.get());
      } catch (RuntimeException releaseFailure) {
        // The lease expires on its own; a failed release must not hide the operation's outcome.
        log.warn(
            "Could not release claim {} ({})", taskName, releaseFailure.getClass().getSimpleName());
      }
    }
  }

  public boolean release(ClaimLease lease) {
    return claimWriter.release(lease.taskName(), lease.claimedUntil());
  }

  private void validate(String taskName, Duration claimDuration) {
    if (taskName == null || taskName.isBlank()) {
      throw new IllegalArgumentException("taskName must not be blank");
    }
    if (claimDuration == null || claimDuration.isZero() || claimDuration.isNegative()) {
      throw new IllegalArgumentException("claimDuration must be positive");
    }
  }

  public record ClaimLease(String taskName, LocalDateTime claimedUntil) {}
}
