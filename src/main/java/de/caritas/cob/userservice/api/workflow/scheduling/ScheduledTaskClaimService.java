package de.caritas.cob.userservice.api.workflow.scheduling;

import java.time.Duration;
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
    if (taskName == null || taskName.isBlank()) {
      throw new IllegalArgumentException("taskName must not be blank");
    }
    if (claimDuration == null || claimDuration.isZero() || claimDuration.isNegative()) {
      throw new IllegalArgumentException("claimDuration must be positive");
    }
    try {
      return claimWriter.claim(taskName, claimDuration);
    } catch (DataAccessException claimConflict) {
      if (claimWriter.hasActiveClaim(taskName)) {
        return false;
      }
      throw claimConflict;
    }
  }
}
