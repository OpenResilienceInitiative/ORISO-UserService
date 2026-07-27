package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ReplicaSafetyMetricsTest {

  @Test
  void shouldExposeConfiguredCeilingAndInventoryWithoutSensitiveTags() throws Exception {
    var registry = new SimpleMeterRegistry();

    newMetrics(registry, 2, 1);

    assertThat(registry.get("userservice.replica.configured").gauge().value()).isEqualTo(2);
    assertThat(registry.get("userservice.replica.supported.max").gauge().value()).isEqualTo(1);
    assertThat(
            registry
                .get("userservice.replica.local_state.components")
                .tag("status", "blocker")
                .gauge()
                .value())
        .isPositive();
    assertThat(
            registry
                .get("userservice.replica.local_state")
                .tags(
                    "component",
                    "matrix-browser-login-locks",
                    "owner",
                    "identity-profile",
                    "risk",
                    "correctness",
                    "status",
                    "blocker")
                .gauge()
                .value())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.scheduler.registered")
                .tags(
                    "task",
                    "enquiry-notification-scheduler",
                    "owner",
                    "notifications",
                    "risk",
                    "duplicate-side-effect",
                    "status",
                    "blocker")
                .gauge()
                .value())
        .isEqualTo(1);
    assertThat(registry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags().toString().toLowerCase())
                    .doesNotContain(
                        "@alice",
                        "!room:",
                        "user-123",
                        "message-123",
                        "secret",
                        "access-token-value"));
  }

  private ReplicaSafetyMetrics newMetrics(
      SimpleMeterRegistry registry, int configured, int supported) {
    return new ReplicaSafetyMetrics(
        registry, new ObjectMapper(), new DefaultResourceLoader(), configured, supported);
  }
}
