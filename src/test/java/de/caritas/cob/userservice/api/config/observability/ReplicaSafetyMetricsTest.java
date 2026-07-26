package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ReplicaSafetyMetricsTest {

  @Test
  void checkedInCatalogPublishesBoundedLocalStateAndSupportedReplicaSignals() {
    var meterRegistry = new SimpleMeterRegistry();
    var metrics = new ReplicaSafetyMetrics(meterRegistry, new ObjectMapper());

    metrics.registerCatalog();

    assertThat(
            meterRegistry
                .get("userservice.replica.local_state")
                .tag("component", "matrix-event-listener-state")
                .tag("owner", "matrix")
                .tag("risk", "duplicate-side-effect")
                .gauge()
                .value())
        .isEqualTo(1);
    assertThat(meterRegistry.get("userservice.replica.max_supported").gauge().value()).isEqualTo(1);
    assertThat(
            meterRegistry.find("userservice.replica.local_state").gauges().stream()
                .map(gauge -> gauge.getId().getTag("component")))
        .doesNotHaveDuplicates()
        .doesNotContainNull();
    assertThat(
            meterRegistry
                .get("userservice.scheduler.registered")
                .tag("task", "group-chat-reminder")
                .tag("owner", "notification")
                .tag("risk", "duplicate-side-effect")
                .gauge()
                .value())
        .isEqualTo(1);
  }
}
