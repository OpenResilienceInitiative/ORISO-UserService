package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double with the claim semantics of {@link ScheduledTaskClaimWriter}: one lease per task name
 * that others cannot take until it expires or is released. It lets unit tests drive a real {@link
 * ScheduledTaskClaimService} with two "replicas" in one JVM. Row-lock timing is covered by {@code
 * ScheduledTaskClaimMariaDbIT}, not here.
 */
public class InMemoryScheduledTaskClaimWriter extends ScheduledTaskClaimWriter {

  private final Map<String, LocalDateTime> leases = new ConcurrentHashMap<>();
  private final Clock testClock;

  public InMemoryScheduledTaskClaimWriter() {
    this(Clock.systemUTC());
  }

  public InMemoryScheduledTaskClaimWriter(Clock clock) {
    super(mock(ScheduledTaskClaimRepository.class), clock);
    this.testClock = clock;
  }

  public static ScheduledTaskClaimService newClaimService() {
    return new ScheduledTaskClaimService(new InMemoryScheduledTaskClaimWriter());
  }

  @Override
  public boolean claim(String taskName, Duration claimDuration) {
    return claimUntil(taskName, claimDuration).isPresent();
  }

  @Override
  public Optional<LocalDateTime> claimUntil(String taskName, Duration claimDuration) {
    var now = LocalDateTime.now(testClock);
    var until = now.plus(claimDuration);
    var winner = new boolean[1];
    leases.compute(
        taskName,
        (name, current) -> {
          if (current != null && current.isAfter(now)) {
            return current;
          }
          winner[0] = true;
          return until;
        });
    return winner[0] ? Optional.of(until) : Optional.empty();
  }

  @Override
  public boolean runIfHeld(ScheduledTaskClaimService.ClaimLease lease, Runnable operation) {
    var held = leases.get(lease.taskName());
    if (held == null
        || !held.equals(lease.claimedUntil())
        || !held.isAfter(LocalDateTime.now(testClock))) {
      return false;
    }
    operation.run();
    return true;
  }

  @Override
  public boolean release(String taskName, LocalDateTime claimedUntil) {
    return leases.remove(taskName, claimedUntil);
  }

  @Override
  public boolean hasActiveClaim(String taskName) {
    var held = leases.get(taskName);
    return held != null && held.isAfter(LocalDateTime.now(testClock));
  }
}
