package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskClaimServiceTest {

  @Mock private ScheduledTaskClaimWriter claimWriter;

  @Test
  void tryClaimShouldReturnFalseWhenConcurrentReplicaCreatesFirstClaim() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    when(claimWriter.claim("task", duration))
        .thenThrow(new DataIntegrityViolationException("duplicate task_name"));
    when(claimWriter.hasActiveClaim("task")).thenReturn(true);

    assertThat(service.tryClaim("task", duration)).isFalse();
  }

  @Test
  void tryClaimShouldRetryMariaDbDeadlockInANewTransaction() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    var deadlock =
        new CannotAcquireLockException(
            "deadlock", new SQLException("Deadlock found", "40001", 1213));
    when(claimWriter.claim("task", duration)).thenThrow(deadlock).thenReturn(false);

    assertThat(service.tryClaim("task", duration)).isFalse();
    verify(claimWriter, times(2)).claim("task", duration);
    verify(claimWriter, never()).hasActiveClaim("task");
  }

  @Test
  void tryClaimLeaseShouldRetryMariaDbDeadlockInANewTransaction() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    var claimedUntil = LocalDateTime.of(2026, 9, 11, 18, 30);
    var deadlock =
        new CannotAcquireLockException(
            "deadlock", new SQLException("Deadlock found", "40001", 1213));
    when(claimWriter.claimUntil("task", duration))
        .thenThrow(deadlock)
        .thenReturn(Optional.of(claimedUntil));

    assertThat(service.tryClaimLease("task", duration))
        .contains(new ScheduledTaskClaimService.ClaimLease("task", claimedUntil));
    verify(claimWriter, times(2)).claimUntil("task", duration);
    verify(claimWriter, never()).hasActiveClaim("task");
  }

  @Test
  void tryClaimShouldStopRetryingAfterThreeDeadlocks() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    var deadlock =
        new CannotAcquireLockException(
            "deadlock", new SQLException("Deadlock found", "40001", 1213));
    when(claimWriter.claim("task", duration)).thenThrow(deadlock);
    when(claimWriter.hasActiveClaim("task")).thenReturn(false);

    assertThatThrownBy(() -> service.tryClaim("task", duration)).isSameAs(deadlock);
    verify(claimWriter, times(3)).claim("task", duration);
  }

  @Test
  void tryClaimShouldRethrowDatabaseFailureWhenNoWinningClaimExists() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    var failure = new DataIntegrityViolationException("schema failure");
    when(claimWriter.claim("task", duration)).thenThrow(failure);
    when(claimWriter.hasActiveClaim("task")).thenReturn(false);

    assertThatThrownBy(() -> service.tryClaim("task", duration)).isSameAs(failure);
  }

  @Test
  void tryClaimShouldRejectInvalidContract() {
    var service = new ScheduledTaskClaimService(claimWriter);

    assertThatThrownBy(() -> service.tryClaim(" ", Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.tryClaim("task", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.tryClaim(null, Duration.ofMinutes(30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.tryClaim("task", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void claimLeaseShouldBeReleasedWithItsExactVersion() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    var claimedUntil = LocalDateTime.of(2026, 9, 11, 18, 30);
    when(claimWriter.claimUntil("task", duration)).thenReturn(Optional.of(claimedUntil));
    when(claimWriter.release("task", claimedUntil)).thenReturn(true);

    var lease = service.tryClaimLease("task", duration).orElseThrow();

    assertThat(service.release(lease)).isTrue();
  }

  @Test
  void runIfClaimedShouldRunTheOperationUnderTheLeaseAndReleaseIt() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(2);
    var claimedUntil = LocalDateTime.of(2026, 10, 25, 12, 0);
    var lease = new ScheduledTaskClaimService.ClaimLease("task", claimedUntil);
    when(claimWriter.claimUntil("task", duration)).thenReturn(Optional.of(claimedUntil));
    when(claimWriter.runIfHeld(
            org.mockito.ArgumentMatchers.eq(lease), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return true;
            });
    var ran = new java.util.concurrent.atomic.AtomicInteger();

    assertThat(service.runIfClaimed("task", duration, ran::incrementAndGet)).isTrue();

    assertThat(ran).hasValue(1);
    verify(claimWriter).release("task", claimedUntil);
  }

  @Test
  void runIfClaimedShouldSkipTheOperationWhenAnotherReplicaHoldsTheClaim() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(2);
    when(claimWriter.claimUntil("task", duration)).thenReturn(Optional.empty());
    var ran = new java.util.concurrent.atomic.AtomicInteger();

    assertThat(service.runIfClaimed("task", duration, ran::incrementAndGet)).isFalse();

    assertThat(ran).hasValue(0);
    verify(claimWriter, never())
        .release(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void runIfClaimedShouldReleaseTheLeaseWhenTheOperationFails() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(2);
    var claimedUntil = LocalDateTime.of(2026, 10, 25, 12, 0);
    when(claimWriter.claimUntil("task", duration)).thenReturn(Optional.of(claimedUntil));
    var failure = new IllegalStateException("boom");
    when(claimWriter.runIfHeld(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> service.runIfClaimed("task", duration, () -> {})).isSameAs(failure);

    verify(claimWriter).release("task", claimedUntil);
  }

  @Test
  void runIfClaimedShouldNotLetAFailedReleaseHideTheOperationResult() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(2);
    var claimedUntil = LocalDateTime.of(2026, 10, 25, 12, 0);
    when(claimWriter.claimUntil("task", duration)).thenReturn(Optional.of(claimedUntil));
    when(claimWriter.runIfHeld(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    when(claimWriter.release("task", claimedUntil)).thenThrow(new CannotAcquireLockException("x"));

    assertThat(service.runIfClaimed("task", duration, () -> {})).isTrue();
  }
}
