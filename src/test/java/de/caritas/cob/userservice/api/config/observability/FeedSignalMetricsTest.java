package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.caritas.cob.userservice.api.config.observability.FeedSignalMetrics.Outcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-020 telemetry: one counter, one tag, and no identifiers anywhere. */
class FeedSignalMetricsTest {

  @Test
  void shouldCountEachOutcomeUnderTheOutcomeTag() {
    var registry = new SimpleMeterRegistry();
    var metrics = new FeedSignalMetrics(registry);

    metrics.record(Outcome.SENT);
    metrics.record(Outcome.SENT);
    metrics.record(Outcome.COALESCED);

    assertThat(
            registry.get(FeedSignalMetrics.FEED_SIGNAL).tags("outcome", "sent").counter().count())
        .isEqualTo(2);
    assertThat(
            registry
                .get(FeedSignalMetrics.FEED_SIGNAL)
                .tags("outcome", "coalesced")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(Outcome.class)
  void everyOutcomeIsRecordableAndCarriesOnlyTheOutcomeTag(Outcome outcome) {
    var registry = new SimpleMeterRegistry();

    new FeedSignalMetrics(registry).record(outcome);

    var meter = onlyMeter(registry);
    assertThat(meter.getId().getName()).isEqualTo("oriso.matrix.feed_signal");
    assertThat(meter.getId().getTags()).hasSize(1);
    assertThat(meter.getId().getTag("outcome")).isNotBlank();
  }

  @Test
  void tagsCarryNoUserRoomTenantOrOtherIdentifier() {
    var registry = new SimpleMeterRegistry();
    var metrics = new FeedSignalMetrics(registry);

    for (var outcome : Outcome.values()) {
      metrics.record(outcome);
    }

    StreamSupport.stream(registry.getMeters().spliterator(), false)
        .forEach(
            meter -> {
              var tagKeys =
                  meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey);
              assertThat(tagKeys).containsOnly("outcome");
              // Belt and braces: no tag value may look like an identifier.
              assertThat(meter.getId().getTags().toString())
                  .doesNotContainIgnoringCase("userid")
                  .doesNotContainIgnoringCase("recipient")
                  .doesNotContainIgnoringCase("matrix.example")
                  .doesNotContainIgnoringCase("tenant")
                  .doesNotContainIgnoringCase("agency")
                  .doesNotContain("@")
                  .doesNotContain("!");
            });
  }

  @Test
  void aBrokenRegistryNeverBreaksTheCaller() {
    MeterRegistry exploding =
        new SimpleMeterRegistry() {
          @Override
          public io.micrometer.core.instrument.Counter counter(
              String name, Iterable<io.micrometer.core.instrument.Tag> tags) {
            throw new IllegalStateException("registry unavailable");
          }
        };

    assertThatCode(() -> new FeedSignalMetrics(exploding).record(Outcome.SENT))
        .doesNotThrowAnyException();
  }

  private Meter onlyMeter(MeterRegistry registry) {
    var meters = StreamSupport.stream(registry.getMeters().spliterator(), false).toList();
    assertThat(meters).hasSize(1);
    return meters.get(0);
  }
}
