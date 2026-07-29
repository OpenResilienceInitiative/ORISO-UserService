package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConsultantAgencyFallbackTelemetryTest {

  @Test
  void shouldCountEveryFallbackButBoundWarningsWithinTheConfiguredWindow() {
    var registry = new SimpleMeterRegistry();
    var clock = Mockito.mock(Clock.class);
    when(clock.millis()).thenReturn(1_000L, 1_001L, 61_000L);
    var telemetry = new ConsultantAgencyFallbackTelemetry(registry, clock, Duration.ofMinutes(1));

    assertThat(telemetry.record(ConsultantAgencyFallbackTelemetry.Reason.DEPENDENCY_ERROR))
        .hasValue(0);
    assertThat(telemetry.record(ConsultantAgencyFallbackTelemetry.Reason.DEPENDENCY_ERROR))
        .isEmpty();
    assertThat(telemetry.record(ConsultantAgencyFallbackTelemetry.Reason.DEPENDENCY_ERROR))
        .hasValue(1);

    assertThat(
            registry
                .get("userservice.dependency.fallbacks")
                .tags(
                    "dependency",
                    "agency-service",
                    "operation",
                    "consultant-agency-batch",
                    "reason",
                    "dependency-error")
                .counter()
                .count())
        .isEqualTo(3);
  }

  @Test
  void shouldAllowOnlyOneWarningAcrossConcurrentFallbacks() throws Exception {
    var registry = new SimpleMeterRegistry();
    var telemetry =
        new ConsultantAgencyFallbackTelemetry(
            registry,
            Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
            Duration.ofMinutes(1));

    try (var executor = Executors.newFixedThreadPool(16)) {
      var decisions =
          executor.invokeAll(
              IntStream.range(0, 100)
                  .<java.util.concurrent.Callable<OptionalLong>>mapToObj(
                      ignored ->
                          () ->
                              telemetry.record(
                                  ConsultantAgencyFallbackTelemetry.Reason.EMPTY_RESPONSE))
                  .toList());

      assertThat(
              decisions.stream()
                  .map(
                      future -> {
                        try {
                          return future.get();
                        } catch (Exception exception) {
                          throw new AssertionError(exception);
                        }
                      })
                  .filter(OptionalLong::isPresent))
          .hasSize(1);
    }

    assertThat(
            registry
                .get("userservice.dependency.fallbacks")
                .tag("reason", "empty-response")
                .counter()
                .count())
        .isEqualTo(100);
  }
}
