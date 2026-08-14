package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Privacy-safe, bounded diagnostic signals for the live-chat and Matrix lifecycle. */
@Component
@RequiredArgsConstructor
public class LiveChatDiagnosticMetrics {

  static final String ROUTING_DECISIONS = "oriso.live_chat.routing.decisions";
  static final String ROUTING_CANDIDATES = "oriso.live_chat.routing.candidates";
  static final String QUEUE_VISIBILITY = "oriso.live_chat.queue.visibility";
  static final String QUEUE_DEPTH = "oriso.live_chat.queue.depth";
  static final String ROOM_CREATION = "oriso.matrix.room.creation";
  static final String EVENT_PROCESSING = "oriso.matrix.event.processing";
  static final String SIDE_EFFECTS = "oriso.matrix.side_effect.operations";

  private final MeterRegistry meterRegistry;

  public void recordRouting(RoutingStage stage, RoutingOutcome outcome, int candidateCount) {
    safely(
        () -> {
          Counter.builder(ROUTING_DECISIONS)
              .description("Bounded live-chat routing decisions")
              .tags("stage", stage.value, "outcome", outcome.value)
              .register(meterRegistry)
              .increment();
          DistributionSummary.builder(ROUTING_CANDIDATES)
              .description("Candidate count observed at a live-chat routing stage")
              .tags("stage", stage.value, "outcome", outcome.value)
              .register(meterRegistry)
              .record(Math.max(candidateCount, 0));
        });
  }

  public void recordQueueDepth(long depth) {
    long boundedDepth = Math.max(depth, 0);
    safely(
        () -> {
          Counter.builder(QUEUE_VISIBILITY)
              .description("Live-chat queue observations grouped by whether demand exists")
              .tags("demand", boundedDepth == 0 ? "none" : "present", "outcome", "observed")
              .register(meterRegistry)
              .increment();
          DistributionSummary.builder(QUEUE_DEPTH)
              .description("Number of pending live-chat enquiries ahead of a request")
              .register(meterRegistry)
              .record(boundedDepth);
        });
  }

  public void recordInvalidQueueRequest() {
    safely(
        () ->
            Counter.builder(QUEUE_VISIBILITY)
                .description("Live-chat queue observations grouped by whether demand exists")
                .tags("demand", "unknown", "outcome", "invalid_request")
                .register(meterRegistry)
                .increment());
  }

  public void recordRoomCreation(boolean encryptionEnabled, Outcome outcome) {
    counter(ROOM_CREATION, "encryption", encryptionEnabled ? "enabled" : "disabled", outcome);
  }

  public void recordMatrixEvent(String rawEventType, Outcome outcome) {
    counter(EVENT_PROCESSING, "event_type", boundedEventType(rawEventType), outcome);
  }

  public void recordSideEffect(SideEffect sideEffect, Outcome outcome) {
    counter(SIDE_EFFECTS, "side_effect", sideEffect.value, outcome);
  }

  private void counter(String name, String tagName, String tagValue, Outcome outcome) {
    safely(
        () ->
            Counter.builder(name)
                .tags(tagName, tagValue, "outcome", outcome.value)
                .register(meterRegistry)
                .increment());
  }

  private void safely(Runnable recording) {
    try {
      recording.run();
    } catch (RuntimeException ignored) {
      // Telemetry must never alter the live-chat or Matrix business outcome.
    }
  }

  private String boundedEventType(String rawEventType) {
    if (rawEventType == null) {
      return "other";
    }
    return switch (rawEventType) {
      case "m.room.message" -> "message";
      case "m.room.encrypted" -> "encrypted_message";
      case "m.call.invite" -> "call_invite";
      case "m.call.answer" -> "call_answer";
      case "m.call.hangup" -> "call_hangup";
      default -> "other";
    };
  }

  public enum RoutingStage {
    AVAILABILITY("availability"),
    ELIGIBILITY("eligibility");

    private final String value;

    RoutingStage(String value) {
      this.value = value;
    }
  }

  public enum RoutingOutcome {
    AVAILABLE("available"),
    INVALID_TOPIC("invalid_topic"),
    NO_ASSIGNMENT("no_assignment"),
    NO_ELIGIBLE_CONSULTANT("no_eligible_consultant"),
    AVAILABILITY_EXPIRED("availability_expired"),
    PRESENCE_UNAVAILABLE("presence_unavailable");

    private final String value;

    RoutingOutcome(String value) {
      this.value = value;
    }
  }

  public enum Outcome {
    SUCCESS("success"),
    FAILURE("failure"),
    SKIPPED("skipped");

    private final String value;

    Outcome(String value) {
      this.value = value;
    }
  }

  public enum SideEffect {
    MOBILE_PUSH("mobile_push"),
    NOTIFICATION("notification");

    private final String value;

    SideEffect(String value) {
      this.value = value;
    }
  }
}
