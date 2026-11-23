package de.caritas.cob.userservice.api.config.observability;

import de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Signals for the DPA signed-notice ledger (ORISO-UserService#1341). The notice is deduplicated by
 * a claim row; releasing that claim is what allows a second notice for the same signature, so every
 * release is counted by the reason it happened.
 *
 * <p>Tags come from enums only. Tenant ids, mail addresses and signature data must never enter a
 * metric series.
 */
@Component
@RequiredArgsConstructor
public class DpaSignedNoticeMetrics {

  static final String DISPATCH = "oriso.dpa_signed_notice.dispatch";
  static final String CLAIM_RELEASED = "oriso.dpa_signed_notice.claim.released";

  private final MeterRegistry meterRegistry;

  /** The notice reached the SMTP handover; the claim stays and blocks every later hint. */
  public void recordSent() {
    counter(DISPATCH, "outcome", "sent");
  }

  /**
   * The claim was released before the handover, so a later hint will try again. {@code
   * DELIVERY_UNCERTAIN} is the series to watch: it is the only one that can produce a second notice
   * for a signature whose first notice may already have arrived.
   */
  public void recordClaimReleased(DeliveryDisposition disposition) {
    counter(DISPATCH, "outcome", "released");
    counter(CLAIM_RELEASED, "disposition", tagOf(disposition));
  }

  /** The release itself failed: this signature can never produce a notice any more. */
  public void recordClaimStuck() {
    counter(DISPATCH, "outcome", "claim_stuck");
  }

  private String tagOf(DeliveryDisposition disposition) {
    if (disposition == null) {
      return "unknown";
    }
    return switch (disposition) {
      case CONFIRMED_NOT_SENT -> "confirmed_not_sent";
      case DELIVERY_UNCERTAIN -> "delivery_uncertain";
    };
  }

  private void counter(String name, String tagName, String tagValue) {
    try {
      Counter.builder(name).tags(tagName, tagValue).register(meterRegistry).increment();
    } catch (RuntimeException ignored) {
      // Telemetry must never change whether a notice is sent.
    }
  }
}
