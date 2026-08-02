package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HandshakeOutboxCompletionServiceTest {

  @InjectMocks private HandshakeOutboxCompletionService completionService;
  @Mock private HandshakeOutboxEventRepository outboxRepository;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(completionService, "maxAttempts", 3);
    ReflectionTestUtils.setField(completionService, "retryBaseSeconds", 30L);
    ReflectionTestUtils.setField(completionService, "retryMaxSeconds", 900L);
  }

  @Test
  void markPublished_Should_CloseOutAClaimedJob() {
    var event = event(OutboxStatus.PROCESSING, 0);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));

    completionService.markPublished(1L);

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(event.getProcessedDate()).isNotNull();
    assertThat(event.getLastError()).isNull();
  }

  @Test
  void markPublished_Should_RefuseAJobThatWasNeverClaimed() {
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event(OutboxStatus.PENDING, 0)));

    assertThatThrownBy(() -> completionService.markPublished(1L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void recordFailure_Should_RescheduleWithBackoffWhileAttemptsRemain() {
    var event = event(OutboxStatus.PROCESSING, 0);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));

    completionService.recordFailure(1L, new IllegalStateException("matrix down"), false);

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(event.getLastError()).isEqualTo("matrix down");
    assertThat(event.getNextAttemptDate()).isAfter(nowInUtc());
  }

  @Test
  void recordFailure_Should_GiveUpOnBoundedJobsAtTheAttemptLimit() {
    var event = event(OutboxStatus.PROCESSING, 2);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));

    completionService.recordFailure(1L, new IllegalStateException("matrix down"), false);

    assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
  }

  @Test
  void recordFailure_Should_NeverGiveUpOnWithdrawal() {
    var event = event(OutboxStatus.PROCESSING, 99);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));

    completionService.recordFailure(1L, new IllegalStateException("homeserver down"), true);

    // Anything terminal here would claim an access was withdrawn that may still be usable.
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
  }

  @Test
  void recordFailure_Should_CapTheBackoff() {
    var event = event(OutboxStatus.PROCESSING, 40);
    when(outboxRepository.findById(1L)).thenReturn(Optional.of(event));

    completionService.recordFailure(1L, new IllegalStateException("still down"), true);

    assertThat(event.getNextAttemptDate()).isBefore(nowInUtc().plusSeconds(901));
  }

  private HandshakeOutboxEvent event(OutboxStatus status, int attempts) {
    return HandshakeOutboxEvent.builder()
        .id(1L)
        .aggregateId("hs-1")
        .eventType(SupportAccessJobHandler.PROVISION_ROOM)
        .status(status)
        .attempts(attempts)
        .createDate(nowInUtc())
        .nextAttemptDate(nowInUtc())
        .build();
  }
}
