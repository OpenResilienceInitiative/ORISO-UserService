package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import org.apache.http.client.config.RequestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
    var restTemplate = new AppConfig().restTemplate(new RestTemplateBuilder());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  @Test
  void matrixLongPollRestTemplateShouldUseLongPollReadTimeout() {
    var restTemplate = new AppConfig().matrixLongPollRestTemplate(new RestTemplateBuilder());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, MATRIX_LONG_POLL_READ_TIMEOUT_MS);
  }

  @Test
  void keycloakRestTemplateShouldUseDefaultTimeouts() {
    var restTemplate = new KeycloakConfig().keycloakRestTemplate(new RestTemplateBuilder());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  @Test
  void rocketChatRestTemplateShouldUseDefaultTimeouts() {
    var restTemplate = new RocketChatConfig(null).rocketChatRestTemplate(new RestTemplateBuilder());

    assertTimeouts(restTemplate, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  private void assertTimeouts(RestTemplate restTemplate, int connectTimeout, int readTimeout) {
    var requestFactory = restTemplate.getRequestFactory();
    var requestConfig =
        (RequestConfig) ReflectionTestUtils.getField(requestFactory, "requestConfig");

    assertThat(requestConfig.getConnectTimeout()).isEqualTo(connectTimeout);
    assertThat(requestConfig.getSocketTimeout()).isEqualTo(readTimeout);
  }
}
