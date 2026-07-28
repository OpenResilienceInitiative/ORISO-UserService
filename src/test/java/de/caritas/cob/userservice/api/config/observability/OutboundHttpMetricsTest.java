package de.caritas.cob.userservice.api.config.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.observation.ClientRequestObservationContext;
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
    var latency =
        registry
            .get(OutboundHttpMetrics.LATENCY)
            .tags("dependency", "matrix.example", "method", "post", "outcome", "2xx")
            .timer();
    assertThat(latency.count()).isEqualTo(1);
    assertThat(latency.takeSnapshot().histogramCounts()).isNotEmpty();
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

  @Test
  void shouldBoundStandardClientMetricUrisWithoutLosingTemplates() {
    var request = org.mockito.Mockito.mock(ClientHttpRequest.class);
    org.mockito.Mockito.when(request.getMethod()).thenReturn(HttpMethod.GET);
    org.mockito.Mockito.when(request.getURI())
        .thenReturn(
            URI.create(
                "https://matrix.example/_matrix/client/r0/sync"
                    + "?since=s10188_dynamic&timeout=30000"));
    var convention = new BoundedClientRequestObservationConvention();
    var context = new ClientRequestObservationContext(request);

    assertThat(uriTag(convention, context)).isEqualTo("untemplated");

    context.setUriTemplate("/_matrix/client/r0/rooms/{roomId}?since={since}");

    assertThat(uriTag(convention, context)).isEqualTo("/_matrix/client/r0/rooms/{roomId}");
  }

  @Test
  void shouldRemovePathsQueriesAndFragmentsFromHighCardinalityClientUrls() {
    var request = org.mockito.Mockito.mock(ClientHttpRequest.class);
    org.mockito.Mockito.when(request.getMethod()).thenReturn(HttpMethod.GET);
    org.mockito.Mockito.when(request.getURI())
        .thenReturn(
            URI.create(
                "https://telemetry-user@matrix.example:8448/_matrix/client/r0/sync"
                    + "?since=s10188_dynamic&access_token=redacted-fixture#fragment"));
    var convention = new BoundedClientRequestObservationConvention();
    var context = new ClientRequestObservationContext(request);

    assertThat(httpUrl(convention, context)).isEqualTo("https://matrix.example:8448");
  }

  @Test
  void shouldInstallBoundedConventionOnEveryCustomizedRestTemplate() {
    var registry = new SimpleMeterRegistry();
    var metrics = new OutboundHttpMetrics(registry);
    var restTemplate = new RestTemplate();

    metrics.customize(restTemplate);
    metrics.customize(restTemplate);

    assertThat(restTemplate.getObservationConvention())
        .isInstanceOf(BoundedClientRequestObservationConvention.class);
    assertThat(restTemplate.getInterceptors()).hasSize(1);
  }

  private String uriTag(
      BoundedClientRequestObservationConvention convention,
      ClientRequestObservationContext context) {
    return StreamSupport.stream(convention.getLowCardinalityKeyValues(context).spliterator(), false)
        .filter(keyValue -> keyValue.getKey().equals("uri"))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private String httpUrl(
      BoundedClientRequestObservationConvention convention,
      ClientRequestObservationContext context) {
    return StreamSupport.stream(
            convention.getHighCardinalityKeyValues(context).spliterator(), false)
        .filter(keyValue -> keyValue.getKey().equals("http.url"))
        .findFirst()
        .orElseThrow()
        .getValue();
  }
}
