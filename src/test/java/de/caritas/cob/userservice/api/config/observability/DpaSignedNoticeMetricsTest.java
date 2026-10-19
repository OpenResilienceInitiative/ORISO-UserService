package de.caritas.cob.userservice.api.config.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.caritas.cob.userservice.api.exception.SmtpSendException.DeliveryDisposition;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tags stay bounded, and a broken registry never changes whether a notice is sent (#1341). */
class DpaSignedNoticeMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DpaSignedNoticeMetrics metrics = new DpaSignedNoticeMetrics(registry);

  @Test
  void recordSent_countsTheDispatchAsSent() {
    metrics.recordSent();

    assertEquals(1.0, count(DpaSignedNoticeMetrics.DISPATCH, "outcome", "sent"));
  }

  @ParameterizedTest
  @EnumSource(DeliveryDisposition.class)
  void recordClaimReleased_countsEveryDispositionUnderItsOwnLowercaseTag(
      DeliveryDisposition disposition) {
    metrics.recordClaimReleased(disposition);

    assertEquals(1.0, count(DpaSignedNoticeMetrics.DISPATCH, "outcome", "released"));
    assertNotNull(
        registry
            .find(DpaSignedNoticeMetrics.CLAIM_RELEASED)
            .tag("disposition", disposition.name().toLowerCase())
            .counter());
  }

  @Test
  void recordClaimReleased_doesNotFail_When_theDispositionIsUnknown() {
    // a non-SMTP failure path could hand in null; telemetry must not throw into the business flow
    metrics.recordClaimReleased(null);

    assertEquals(1.0, count(DpaSignedNoticeMetrics.CLAIM_RELEASED, "disposition", "unknown"));
  }

  @Test
  void recordClaimStuck_countsTheUnrecoverableCase() {
    metrics.recordClaimStuck();

    assertEquals(1.0, count(DpaSignedNoticeMetrics.DISPATCH, "outcome", "claim_stuck"));
  }

  @Test
  void recording_swallowsRegistryFailures() {
    MeterRegistry broken =
        new SimpleMeterRegistry() {
          @Override
          protected io.micrometer.core.instrument.Counter newCounter(
              io.micrometer.core.instrument.Meter.Id id) {
            throw new IllegalStateException("registry down");
          }
        };
    var resilient = new DpaSignedNoticeMetrics(broken);

    assertDoesNotThrow(
        () -> {
          resilient.recordSent();
          resilient.recordClaimReleased(DeliveryDisposition.DELIVERY_UNCERTAIN);
          resilient.recordClaimStuck();
        });
  }

  private double count(String name, String tagName, String tagValue) {
    var counter = registry.find(name).tag(tagName, tagValue).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
