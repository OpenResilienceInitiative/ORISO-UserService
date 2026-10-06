package de.caritas.cob.userservice.api.workflow.scheduling;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Replica-neutral API for claiming one scheduled execution for a bounded interval. */
@Service
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
