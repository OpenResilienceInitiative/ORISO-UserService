package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class RestTemplateTimeoutConfigTest {

  private static final int CONNECT_TIMEOUT_MS =
      Math.toIntExact(RestTemplateTimeouts.CONNECT_TIMEOUT.toMillis());
  private static final int READ_TIMEOUT_MS =
      Math.toIntExact(RestTemplateTimeouts.READ_TIMEOUT.toMillis());
  private static final int MATRIX_LONG_POLL_READ_TIMEOUT_MS =
      Math.toIntExact(RestTemplateTimeouts.MATRIX_LONG_POLL_READ_TIMEOUT.toMillis());

  @Test
  void restTemplateShouldUseDefaultTimeouts() {
    var restTemplate = new AppConfig().restTemplate(new RestTemplateBuilder(), metrics());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  @Test
  void matrixLongPollRestTemplateShouldUseLongPollReadTimeout() {
    var restTemplate =
        new AppConfig().matrixLongPollRestTemplate(new RestTemplateBuilder(), metrics());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, MATRIX_LONG_POLL_READ_TIMEOUT_MS);
  }

  @Test
  void keycloakRestTemplateShouldUseDefaultTimeouts() {
    var restTemplate =
        new KeycloakConfig().keycloakRestTemplate(new RestTemplateBuilder(), metrics());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  @Test
  void ownedRestTemplatesShouldInstallBoundedObservabilityWithoutBuilderAutoConfiguration() {
    var metrics = new OutboundHttpMetrics(new SimpleMeterRegistry());
    var builderWithoutAutoConfiguration = new RestTemplateBuilder();

    assertBoundedObservability(
        new AppConfig().restTemplate(builderWithoutAutoConfiguration, metrics));
    assertBoundedObservability(
        new AppConfig().matrixLongPollRestTemplate(builderWithoutAutoConfiguration, metrics));
    assertBoundedObservability(
        new KeycloakConfig().keycloakRestTemplate(builderWithoutAutoConfiguration, metrics));
  }

  private void assertTimeouts(RestTemplate restTemplate, int connectTimeout, int readTimeout) {
    var requestFactory = restTemplate.getRequestFactory();
    if (requestFactory instanceof AbstractClientHttpRequestFactoryWrapper wrapper) {
      requestFactory = wrapper.getDelegate();
    }
    assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);

    var httpClient = (HttpClient) ReflectionTestUtils.getField(requestFactory, "httpClient");
    var readTimeoutDuration =
        (Duration) ReflectionTestUtils.getField(requestFactory, "readTimeout");

    assertThat(httpClient.connectTimeout()).contains(Duration.ofMillis(connectTimeout));
    assertThat(readTimeoutDuration).isEqualTo(Duration.ofMillis(readTimeout));
  }

  private void assertBoundedObservability(RestTemplate restTemplate) {
    assertThat(restTemplate.getObservationConvention()).isNotNull();
    assertThat(restTemplate.getObservationConvention().getClass().getSimpleName())
        .isEqualTo("BoundedClientRequestObservationConvention");
    assertThat(restTemplate.getInterceptors()).hasSize(1);
  }

  private OutboundHttpMetrics metrics() {
    return new OutboundHttpMetrics(new SimpleMeterRegistry());
  }
}
