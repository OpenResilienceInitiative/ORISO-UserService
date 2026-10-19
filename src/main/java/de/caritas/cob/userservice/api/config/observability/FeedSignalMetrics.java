package de.caritas.cob.userservice.api.config.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Privacy-safe counters for the P2 feed-update signal (ADR-020).
 *
 * <p>The only tag is {@code outcome}. No user, recipient, room, tenant or agency identifier is ever
 * attached: the signal exists precisely so that nothing about a notification leaves the persisted
 * feed, and its telemetry must not undo that.
 *
 * <p>Micrometer meter name {@code oriso.matrix.feed_signal}; Prometheus renders the counter as
 * {@code oriso_matrix_feed_signal_total}.
 */
@Component
@RequiredArgsConstructor
public class FeedSignalMetrics {

  static final String FEED_SIGNAL = "oriso.matrix.feed_signal";

  private final MeterRegistry meterRegistry;

  public void record(Outcome outcome) {
    if (outcome == null) {
      return;
    }
    try {
      Counter.builder(FEED_SIGNAL)
          .description("Attempts to signal one recipient that their notification feed changed")
          .tags("outcome", outcome.value)
          .register(meterRegistry)
          .increment();
    } catch (RuntimeException ignored) {
      // Telemetry must never alter the notification outcome.
    }
  }

  /** Terminal states of one attempt to signal one recipient. */
  public enum Outcome {
    /** A to-device message was accepted by Synapse. */
    SENT("sent"),
    /** Absorbed by a send already scheduled for this recipient. */
    COALESCED("coalesced"),
    /** The recipient has no Matrix identity yet, so there is nobody to signal. */
    SKIPPED_NO_IDENTITY("skipped_no_identity"),
    /** Switched off via {@code matrix.feedSignal.enabled}. */
    DISABLED("disabled"),
    /** Synapse rejected it, the transport threw, or the bounded executor dropped it. */
    FAILED("failed");

    final String value;

    Outcome(String value) {
      this.value = value;
    }
  }
}
