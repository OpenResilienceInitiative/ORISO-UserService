package de.caritas.cob.userservice.api.workflow.scheduling;

import de.caritas.cob.userservice.api.model.ScheduledTaskClaim;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
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
    return claimUntil(taskName, claimDuration).isPresent();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<LocalDateTime> claimUntil(String taskName, Duration claimDuration) {
    LocalDateTime now = LocalDateTime.now(clock);
    var existingClaim = claimRepository.findByTaskNameForUpdate(taskName);
    if (existingClaim.isPresent()) {
      var claim = existingClaim.get();
      if (claim.getClaimedUntil().isAfter(now)) {
        return Optional.empty();
      }
      claim.setClaimedAt(now);
      claim.setClaimedUntil(now.plus(claimDuration));
      claimRepository.saveAndFlush(claim);
      return Optional.of(claim.getClaimedUntil());
    }

    claimRepository.saveAndFlush(
        ScheduledTaskClaim.builder()
            .taskName(taskName)
            .claimedAt(now)
            .claimedUntil(now.plus(claimDuration))
            .build());
    return Optional.of(now.plus(claimDuration));
  }

  /** Deletes only the exact lease version acquired by this execution. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean release(String taskName, LocalDateTime claimedUntil) {
    return claimRepository.deleteByTaskNameAndClaimedUntil(taskName, claimedUntil) == 1;
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
