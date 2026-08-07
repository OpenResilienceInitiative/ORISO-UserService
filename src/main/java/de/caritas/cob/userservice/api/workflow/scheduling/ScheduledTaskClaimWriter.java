package de.caritas.cob.userservice.api.workflow.scheduling;

import de.caritas.cob.userservice.api.model.ScheduledTaskClaim;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the isolated transaction that serializes one durable scheduled-task claim. */
@Service
@RequiredArgsConstructor
public class ScheduledTaskClaimWriter {

  private final @NonNull ScheduledTaskClaimRepository claimRepository;
  private final @NonNull Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(String taskName, Duration claimDuration) {
    LocalDateTime now = LocalDateTime.now(clock);
    var existingClaim = claimRepository.findByTaskNameForUpdate(taskName);
    if (existingClaim.isPresent()) {
      var claim = existingClaim.get();
      if (claim.getClaimedUntil().isAfter(now)) {
        return false;
      }
      claim.setClaimedAt(now);
      claim.setClaimedUntil(now.plus(claimDuration));
      claimRepository.saveAndFlush(claim);
      return true;
    }

    claimRepository.saveAndFlush(
        ScheduledTaskClaim.builder()
            .taskName(taskName)
            .claimedAt(now)
            .claimedUntil(now.plus(claimDuration))
            .build());
    return true;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean hasActiveClaim(String taskName) {
    LocalDateTime now = LocalDateTime.now(clock);
    return claimRepository
        .findById(taskName)
        .map(ScheduledTaskClaim::getClaimedUntil)
        .filter(claimedUntil -> claimedUntil.isAfter(now))
        .isPresent();
  }
}
