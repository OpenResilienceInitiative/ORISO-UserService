package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LiveChatDiagnosticMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final LiveChatDiagnosticMetrics metrics = new LiveChatDiagnosticMetrics(registry);

  @Test
  void recordsBoundedRoutingAndQueueDiagnostics() {
    metrics.recordRouting(
        LiveChatDiagnosticMetrics.RoutingStage.AVAILABILITY,
        LiveChatDiagnosticMetrics.RoutingOutcome.AVAILABILITY_EXPIRED,
        3);
    metrics.recordQueueDepth(0);

    assertThat(
            registry
                .get("oriso.live_chat.routing.decisions")
                .tag("stage", "availability")
                .tag("outcome", "availability_expired")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("oriso.live_chat.routing.candidates")
                .tag("stage", "availability")
                .tag("outcome", "availability_expired")
                .summary()
                .totalAmount())
        .isEqualTo(3);
    assertThat(
            registry
                .get("oriso.live_chat.queue.visibility")
                .tag("demand", "none")
                .tag("outcome", "observed")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void distinguishesInvalidQueueRequestsFromObservedEmptyDemand() {
    metrics.recordInvalidQueueRequest();

    assertThat(
            registry
                .get("oriso.live_chat.queue.visibility")
                .tag("demand", "unknown")
                .tag("outcome", "invalid_request")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.find("oriso.live_chat.queue.depth").summary()).isNull();
  }

  @Test
  void recordsRoomEncryptionAtTheCompletedBoundary() {
    metrics.recordRoomCreation(true, LiveChatDiagnosticMetrics.Outcome.SUCCESS);
    metrics.recordRoomCreation(false, LiveChatDiagnosticMetrics.Outcome.FAILURE);

    assertThat(
            registry
                .get("oriso.matrix.room.creation")
                .tag("encryption", "enabled")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("oriso.matrix.room.creation")
                .tag("encryption", "disabled")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void boundsRawMatrixEventTypesAndSideEffects() {
    metrics.recordMatrixEvent("m.room.encrypted", LiveChatDiagnosticMetrics.Outcome.SUCCESS);
    metrics.recordMatrixEvent("m.room.secret-user-123", LiveChatDiagnosticMetrics.Outcome.FAILURE);
    metrics.recordSideEffect(
        LiveChatDiagnosticMetrics.SideEffect.NOTIFICATION,
        LiveChatDiagnosticMetrics.Outcome.FAILURE);

    assertThat(
            registry
                .get("oriso.matrix.event.processing")
                .tag("event_type", "encrypted_message")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("oriso.matrix.event.processing")
                .tag("event_type", "other")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.getMeters().stream().map(meter -> meter.getId().getTags().toString()))
        .noneMatch(tags -> tags.contains("secret-user-123"));
    assertThat(
            registry
                .get("oriso.matrix.side_effect.operations")
                .tag("side_effect", "notification")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void neverChangesBusinessOutcomeWhenTheRegistryFails() {
    var failingMetrics = new LiveChatDiagnosticMetrics(new FailingCounterMeterRegistry());

    assertThatCode(
            () ->
                failingMetrics.recordRoomCreation(false, LiveChatDiagnosticMetrics.Outcome.SUCCESS))
        .doesNotThrowAnyException();
  }

  private static final class FailingCounterMeterRegistry extends SimpleMeterRegistry {

    @Override
    protected Counter newCounter(Meter.Id id) {
      throw new IllegalStateException("registry unavailable");
    }
  }
}
