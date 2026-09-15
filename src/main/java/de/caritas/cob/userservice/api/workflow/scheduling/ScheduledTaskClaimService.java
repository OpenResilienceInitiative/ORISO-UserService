package de.caritas.cob.userservice.api.workflow.scheduling;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Replica-neutral API for claiming one scheduled execution for a bounded interval. */
@Service
@RequiredArgsConstructor
public class ScheduledTaskClaimService {

  private final @NonNull ScheduledTaskClaimWriter claimWriter;

  public boolean tryClaim(String taskName, Duration claimDuration) {
    validate(taskName, claimDuration);
    try {
      return claimWriter.claim(taskName, claimDuration);
    } catch (DataAccessException claimConflict) {
      if (claimWriter.hasActiveClaim(taskName)) {
        return false;
      }
      throw claimConflict;
    }
  }

  public Optional<ClaimLease> tryClaimLease(String taskName, Duration claimDuration) {
    validate(taskName, claimDuration);
    try {
      return claimWriter
          .claimUntil(taskName, claimDuration)
          .map(claimedUntil -> new ClaimLease(taskName, claimedUntil));
    } catch (DataAccessException claimConflict) {
      if (claimWriter.hasActiveClaim(taskName)) {
        return Optional.empty();
      }
      throw claimConflict;
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
