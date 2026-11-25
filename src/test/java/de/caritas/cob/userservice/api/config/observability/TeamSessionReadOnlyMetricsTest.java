package de.caritas.cob.userservice.api.config.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TeamSessionReadOnlyMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final TeamSessionReadOnlyMetrics metrics = new TeamSessionReadOnlyMetrics(registry);

  @Test
  void countsPendingCasesByReason() {
    metrics.recordModeUnresolved();
    metrics.recordModeUnresolved();
    metrics.recordNoCentre();

    assertEquals(
        2.0,
        registry
            .get(TeamSessionReadOnlyMetrics.PENDING)
            .tag("reason", "mode_unresolved")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .get(TeamSessionReadOnlyMetrics.PENDING)
            .tag("reason", "no_centre")
            .counter()
            .count());
  }
}
