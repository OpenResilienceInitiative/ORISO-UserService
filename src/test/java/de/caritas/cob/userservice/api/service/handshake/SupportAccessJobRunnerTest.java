package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.service.support.SupportAccessMatrixWorker;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The whole retry contract now lives in one place, so it is tested in one place: claim once,
 * dispatch, and either publish or reschedule with the give-up policy the job itself declares.
 */
@ExtendWith(MockitoExtension.class)
class SupportAccessJobRunnerTest {

  @InjectMocks private SupportAccessJobRunner runner;

  @Mock private HandshakeOutboxEventRepository outboxRepository;
  @Mock private SupportAccessMatrixWorker worker;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(runner, "batchSize", 20);
    ReflectionTestUtils.setField(runner, "maxAttempts", 3);
    ReflectionTestUtils.setField(runner, "retryBaseSeconds", 30L);
    ReflectionTestUtils.setField(runner, "retryMaxSeconds", 900L);
  }

  @Test
  void processPending_Should_ClaimThenDispatchThenPublish() {
    var event = event(1L, SupportAccessJob.PROVISION_ROOM, "hs-1", OutboxStatus.PROCESSING, 0);
    givenPending(event);
    when(outboxRepository.claim(1L)).thenReturn(1);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));

    runner.processPending();

    verify(worker).provision("hs-1");
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
  }

  @Test
  void processPending_Should_DoNothingWhenTheClaimWasLost() {
    givenPending(event(1L, SupportAccessJob.PROVISION_ROOM, "hs-1", OutboxStatus.PENDING, 0));
    when(outboxRepository.claim(1L)).thenReturn(0);

    runner.processPending();

    verify(worker, never()).provision(any());
  }

  @Test
  void processPending_Should_RouteEachJobToItsOwnWorkerCall() {
    var revoke = event(2L, SupportAccessJob.REVOKE_ACCESS, "sess-1", OutboxStatus.PROCESSING, 0);
    givenPending(revoke);
    when(outboxRepository.claim(2L)).thenReturn(1);
    when(outboxRepository.findById(2L)).thenReturn(Optional.of(revoke));

    runner.processPending();

    verify(worker).revoke("sess-1");
    verify(worker, never()).provision(any());
  }

  @Test
  void processPending_Should_GiveUpOnProvisioningAtTheAttemptLimit() {
    var event = event(1L, SupportAccessJob.PROVISION_ROOM, "hs-1", OutboxStatus.PROCESSING, 2);
    givenPending(event);
    when(outboxRepository.claim(1L)).thenReturn(1);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));
    doThrow(new IllegalStateException("matrix down")).when(worker).provision("hs-1");

    runner.processPending();

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(event.getLastError()).isEqualTo("matrix down");
  }

  @Test
  void processPending_Should_NeverGiveUpOnWithdrawal() {
    var event = event(2L, SupportAccessJob.REVOKE_ACCESS, "sess-1", OutboxStatus.PROCESSING, 99);
    givenPending(event);
    when(outboxRepository.claim(2L)).thenReturn(1);
    when(outboxRepository.findById(2L)).thenReturn(Optional.of(event));
    doThrow(new IllegalStateException("homeserver down")).when(worker).revoke("sess-1");

    runner.processPending();

    // Anything terminal here would claim an access was withdrawn that may still be usable.
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    // ...and the backoff stays capped rather than growing without bound.
    assertThat(event.getNextAttemptDate()).isBefore(nowInUtc().plusSeconds(901));
  }

  @Test
  void processPending_Should_NotSilentlySkipAnUnknownJobType() {
    var event =
        HandshakeOutboxEvent.builder()
            .id(3L)
            .aggregateId("x")
            .eventType("SOMETHING_ELSE")
            .status(OutboxStatus.PROCESSING)
            .attempts(0)
            .createDate(nowInUtc())
            .nextAttemptDate(nowInUtc())
            .build();
    givenPending(event);
    when(outboxRepository.claim(3L)).thenReturn(1);
    when(outboxRepository.findById(3L)).thenReturn(Optional.of(event));

    runner.processPending();

    // Unknown means broken deployment, not "retry forever": it must surface.
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event.getLastError()).contains("SOMETHING_ELSE");
  }

  private void givenPending(HandshakeOutboxEvent event) {
    when(outboxRepository.findAllByStatusAndNextAttemptDateBeforeOrderById(any(), any(), any()))
        .thenReturn(List.of(event));
  }

  private HandshakeOutboxEvent event(
      Long id, SupportAccessJob job, String aggregateId, OutboxStatus status, int attempts) {
    return HandshakeOutboxEvent.builder()
        .id(id)
        .aggregateId(aggregateId)
        .eventType(job.name())
        .status(status)
        .attempts(attempts)
        .createDate(nowInUtc())
        .nextAttemptDate(nowInUtc())
        .build();
  }
}
