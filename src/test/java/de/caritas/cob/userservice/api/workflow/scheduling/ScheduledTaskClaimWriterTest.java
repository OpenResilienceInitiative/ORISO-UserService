package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ScheduledTaskClaim;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskClaimWriterTest {

  private static final String TASK_NAME = "task";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 8, 0);

  @Mock private ScheduledTaskClaimRepository claimRepository;

  private ScheduledTaskClaimWriter claimWriter;

  @BeforeEach
  void setUp() {
    var clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    claimWriter = new ScheduledTaskClaimWriter(claimRepository, clock);
  }

  @Test
  void claimShouldCreateFirstClaim() {
    when(claimRepository.findByTaskNameForUpdate(TASK_NAME)).thenReturn(Optional.empty());

    boolean claimed = claimWriter.claim(TASK_NAME, Duration.ofMinutes(30));

    assertThat(claimed).isTrue();
    var captor = ArgumentCaptor.forClass(ScheduledTaskClaim.class);
    verify(claimRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getTaskName()).isEqualTo(TASK_NAME);
    assertThat(captor.getValue().getClaimedAt()).isEqualTo(NOW);
    assertThat(captor.getValue().getClaimedUntil()).isEqualTo(NOW.plusMinutes(30));
    // A fresh claim must report itself as new so Spring Data INSERTs it. A merge here would
    // silently UPDATE a row a competing replica committed in the race window, letting two
    // replicas run the same scheduled task (flaky DeleteUsersRegisteredOnlySchedulerReplicaIT).
    assertThat(captor.getValue().isNew()).isTrue();
    assertThat(captor.getValue().getId()).isEqualTo(TASK_NAME);
  }

  @Test
  void claimShouldRejectActiveClaim() {
    var activeClaim =
        ScheduledTaskClaim.builder()
            .taskName(TASK_NAME)
            .claimedAt(NOW.minusMinutes(5))
            .claimedUntil(NOW.plusMinutes(25))
            .build();
    when(claimRepository.findByTaskNameForUpdate(TASK_NAME)).thenReturn(Optional.of(activeClaim));

    boolean claimed = claimWriter.claim(TASK_NAME, Duration.ofMinutes(30));

    assertThat(claimed).isFalse();
    verify(claimRepository, never()).saveAndFlush(activeClaim);
  }

  @Test
  void claimShouldRenewExpiredClaim() {
    var expiredClaim =
        ScheduledTaskClaim.builder()
            .taskName(TASK_NAME)
            .claimedAt(NOW.minusHours(1))
            .claimedUntil(NOW.minusMinutes(30))
            .build();
    when(claimRepository.findByTaskNameForUpdate(TASK_NAME)).thenReturn(Optional.of(expiredClaim));

    boolean claimed = claimWriter.claim(TASK_NAME, Duration.ofMinutes(30));

    assertThat(claimed).isTrue();
    assertThat(expiredClaim.getClaimedAt()).isEqualTo(NOW);
    assertThat(expiredClaim.getClaimedUntil()).isEqualTo(NOW.plusMinutes(30));
    verify(claimRepository).saveAndFlush(expiredClaim);
  }

  @Test
  void hasActiveClaimShouldDistinguishActiveAndExpiredClaims() {
    var activeClaim =
        ScheduledTaskClaim.builder()
            .taskName(TASK_NAME)
            .claimedAt(NOW.minusMinutes(5))
            .claimedUntil(NOW.plusMinutes(25))
            .build();
    when(claimRepository.findById(TASK_NAME)).thenReturn(Optional.of(activeClaim));

    assertThat(claimWriter.hasActiveClaim(TASK_NAME)).isTrue();

    activeClaim.setClaimedUntil(NOW);
    assertThat(claimWriter.hasActiveClaim(TASK_NAME)).isFalse();
  }
}
