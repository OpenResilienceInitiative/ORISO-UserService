package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskClaimServiceTest {

  @Mock private ScheduledTaskClaimWriter claimWriter;

  @Test
  void tryClaim_Should_returnFalse_When_concurrentReplicaCreatesFirstClaim() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    when(claimWriter.claim("task", duration))
        .thenThrow(new DataIntegrityViolationException("duplicate task_name"));
    when(claimWriter.hasActiveClaim("task")).thenReturn(true);

    assertThat(service.tryClaim("task", duration)).isFalse();
  }

  @Test
  void tryClaim_Should_rethrowDatabaseFailure_When_noWinningClaimExists() {
    var service = new ScheduledTaskClaimService(claimWriter);
    var duration = Duration.ofMinutes(30);
    var failure = new DataIntegrityViolationException("schema failure");
    when(claimWriter.claim("task", duration)).thenThrow(failure);
    when(claimWriter.hasActiveClaim("task")).thenReturn(false);

    assertThatThrownBy(() -> service.tryClaim("task", duration)).isSameAs(failure);
  }

  @Test
  void tryClaim_Should_rejectInvalidContract() {
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
}
