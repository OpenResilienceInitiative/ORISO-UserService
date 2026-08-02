package de.caritas.cob.userservice.api.service.handshake;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HandshakeOutboxProcessorTest {

  private HandshakeOutboxProcessor processor;
  @Mock private HandshakeOutboxEventRepository outboxRepository;
  @Mock private HandshakeOutboxCompletionService completionService;

  private final RecordingHandler provisionHandler =
      new RecordingHandler(SupportAccessJobHandler.PROVISION_ROOM, false);
  private final RecordingHandler revokeHandler =
      new RecordingHandler(SupportAccessJobHandler.REVOKE_ACCESS, true);

  @BeforeEach
  void setUp() {
    processor =
        new HandshakeOutboxProcessor(
            outboxRepository, completionService, List.of(provisionHandler, revokeHandler));
    ReflectionTestUtils.setField(processor, "batchSize", 20);
  }

  @Test
  void processPending_ShouldClaimThenRunTheMatchingHandlerThenPublish() {
    givenPending(event(1L, SupportAccessJobHandler.PROVISION_ROOM, "hs-1"));
    when(outboxRepository.claim(1L)).thenReturn(1);

    processor.processPending();

    org.assertj.core.api.Assertions.assertThat(provisionHandler.handled).containsExactly("hs-1");
    org.assertj.core.api.Assertions.assertThat(revokeHandler.handled).isEmpty();
    verify(completionService).markPublished(1L);
  }

  @Test
  void processPending_ShouldDoNothingWhenTheClaimWasLost() {
    givenPending(event(1L, SupportAccessJobHandler.PROVISION_ROOM, "hs-1"));
    when(outboxRepository.claim(1L)).thenReturn(0);

    processor.processPending();

    org.assertj.core.api.Assertions.assertThat(provisionHandler.handled).isEmpty();
    verify(completionService, never()).markPublished(any());
  }

  @Test
  void processPending_ShouldRecordBoundedFailureForProvisioning() {
    provisionHandler.failWith(new IllegalStateException("matrix unavailable"));
    givenPending(event(1L, SupportAccessJobHandler.PROVISION_ROOM, "hs-1"));
    when(outboxRepository.claim(1L)).thenReturn(1);

    processor.processPending();

    verify(completionService).recordFailure(eq(1L), any(RuntimeException.class), eq(false));
    verify(completionService, never()).markPublished(1L);
  }

  @Test
  void processPending_ShouldNeverGiveUpOnWithdrawal() {
    revokeHandler.failWith(new IllegalStateException("homeserver down"));
    givenPending(event(2L, SupportAccessJobHandler.REVOKE_ACCESS, "sess-1"));
    when(outboxRepository.claim(2L)).thenReturn(1);

    processor.processPending();

    // retriesForever=true: a withdrawal job must not end up FAILED, otherwise an outage would look
    // like a completed revocation.
    verify(completionService).recordFailure(eq(2L), any(RuntimeException.class), eq(true));
  }

  @Test
  void processPending_ShouldFailUnknownJobTypesWithoutRetryingForever() {
    givenPending(event(3L, "SOMETHING_ELSE", "x"));
    when(outboxRepository.claim(3L)).thenReturn(1);

    processor.processPending();

    verify(completionService).recordFailure(eq(3L), any(RuntimeException.class), eq(false));
  }

  private void givenPending(HandshakeOutboxEvent event) {
    when(outboxRepository.findAllByStatusAndNextAttemptDateBeforeOrderById(any(), any(), any()))
        .thenReturn(List.of(event));
  }

  private HandshakeOutboxEvent event(Long id, String type, String aggregateId) {
    return HandshakeOutboxEvent.builder()
        .id(id)
        .aggregateId(aggregateId)
        .eventType(type)
        .status(OutboxStatus.PENDING)
        .nextAttemptDate(LocalDateTime.now())
        .build();
  }

  private static final class RecordingHandler implements SupportAccessJobHandler {
    private final String type;
    private final boolean forever;
    private final List<String> handled = new java.util.ArrayList<>();
    private RuntimeException failure;

    RecordingHandler(String type, boolean forever) {
      this.type = type;
      this.forever = forever;
    }

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public String jobType() {
      return type;
    }

    @Override
    public void handle(String aggregateId) {
      if (failure != null) {
        throw failure;
      }
      handled.add(aggregateId);
    }

    @Override
    public boolean retriesForever() {
      return forever;
    }
  }
}
