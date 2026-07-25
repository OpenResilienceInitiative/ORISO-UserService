package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class OutboundHttpMetricsTest {

  @Test
  void shouldMeasureCallsLatencyAndKnownPayloadSizesWithoutPathTags() {
    var registry = new SimpleMeterRegistry();
    var metrics = new OutboundHttpMetrics(registry);
    var restTemplate = new RestTemplate();
    metrics.customize(restTemplate);
    var server = MockRestServiceServer.bindTo(restTemplate).build();
    var responseBody = "{\"ok\":true}";

    server
        .expect(requestTo("https://matrix.example/_matrix/client/v3/sync?since=secret"))
        .andRespond(
            withSuccess(responseBody, APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(responseBody.length())));

    restTemplate.postForEntity(
        "https://matrix.example/_matrix/client/v3/sync?since=secret",
        "{\"request\":true}",
        String.class);

    assertThat(
            registry
                .get(OutboundHttpMetrics.CALLS)
                .tags("dependency", "matrix.example", "method", "post", "outcome", "2xx")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(OutboundHttpMetrics.LATENCY)
                .tags("dependency", "matrix.example", "method", "post", "outcome", "2xx")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(OutboundHttpMetrics.PAYLOAD)
                .tags("dependency", "matrix.example", "direction", "request")
                .summary()
                .totalAmount())
        .isPositive();
    assertThat(
            registry
                .get(OutboundHttpMetrics.PAYLOAD)
                .tags("dependency", "matrix.example", "direction", "response")
                .summary()
                .totalAmount())
        .isEqualTo(responseBody.length());
    assertThat(registry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags().toString())
                    .doesNotContain("_matrix", "since", "secret"));
    server.verify();
  }

  @Test
  void shouldMeasureExplicitRetriesByDependencyAndOperation() {
    var registry = new SimpleMeterRegistry();
    var metrics = new OutboundHttpMetrics(registry);

    metrics.recordRetry("keycloak", "role-visibility");

    assertThat(
            registry
                .get(OutboundHttpMetrics.RETRIES)
                .tags("dependency", "keycloak", "operation", "role-visibility")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
