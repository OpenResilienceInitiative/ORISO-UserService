package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class RestTemplateTimeoutBehaviorTest {

  private static final long DELAY_MS = 12_000L;
  private static final long MIN_TIMEOUT_OBSERVATION_MS = 8_000L;
  private static final long MAX_TIMEOUT_OBSERVATION_MS = 18_000L;
  private static final long MAX_LONG_POLL_SUCCESS_MS = 20_000L;

  private HttpServer httpServer;
  private String slowUrl;

  @BeforeEach
  void setUp() throws Exception {
    httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    httpServer.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(DELAY_MS);
            byte[] response = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(HttpStatus.OK.value(), response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
              outputStream.write(response);
            }
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    httpServer.setExecutor(Executors.newCachedThreadPool());
    httpServer.start();
    slowUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/slow";
  }

  @AfterEach
  void tearDown() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  @Test
  void defaultRestTemplateShouldTimeoutOnSlowResponse() {
    assertReadTimeout(new AppConfig().restTemplate(new RestTemplateBuilder()));
  }

  @Test
  void keycloakRestTemplateShouldTimeoutOnSlowResponse() {
    assertReadTimeout(new KeycloakConfig().keycloakRestTemplate(new RestTemplateBuilder()));
  }

  @Test
  void rocketChatRestTemplateShouldTimeoutOnSlowResponse() {
    assertReadTimeout(new RocketChatConfig(null).rocketChatRestTemplate(new RestTemplateBuilder()));
  }

  @Test
  void matrixLongPollRestTemplateShouldAllowSlowResponseWithinLongPollWindow() {
    var restTemplate = new AppConfig().matrixLongPollRestTemplate(new RestTemplateBuilder());
    var startedAt = Instant.now();

    var response = restTemplate.getForEntity(slowUrl, String.class);

    long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("ok");
    assertThat(elapsedMs).isGreaterThanOrEqualTo(DELAY_MS);
    assertThat(elapsedMs).isLessThan(MAX_LONG_POLL_SUCCESS_MS);
  }

  private void assertReadTimeout(RestTemplate restTemplate) {
    var startedAt = Instant.now();

    assertThatThrownBy(() -> restTemplate.getForEntity(slowUrl, String.class))
        .isInstanceOf(ResourceAccessException.class)
        .hasRootCauseInstanceOf(HttpTimeoutException.class);

    long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
    assertThat(elapsedMs).isGreaterThanOrEqualTo(MIN_TIMEOUT_OBSERVATION_MS);
    assertThat(elapsedMs).isLessThan(MAX_TIMEOUT_OBSERVATION_MS);
  }
}
