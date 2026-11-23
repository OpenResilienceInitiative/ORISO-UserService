package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class BackendClientCredentialHttpTest {
  HttpServer server;
  KeycloakAuthClient client;
  AtomicInteger calls;
  int status;
  String body;
  String form;

  @BeforeEach
  void setup() throws Exception {
    calls = new AtomicInteger();
    status = 200;
    body = "{\"access_token\":\"service-token\",\"expires_in\":300}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          calls.incrementAndGet();
          form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    var config = new IdentityConfig();
    config.setOpenidConnectUrl("http://127.0.0.1:" + server.getAddress().getPort());
    client = new KeycloakAuthClient(new RestTemplate(), mock(AuthenticatedUser.class), config);
    ReflectionTestUtils.setField(client, "keycloakClientId", "app");
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
  }

  @Test
  void authenticServiceResponseNeedsNoHumanRefreshFields() {
    var response = client.loginService("backend-technical", "synthetic-secret");
    assertThat(response.getAccessToken()).isEqualTo("service-token");
    assertThat(response.getRefreshToken()).isNull();
    assertThat(response.getRefreshExpiresIn()).isZero();
    assertThat(form)
        .contains(
            "grant_type=client_credentials",
            "client_id=backend-technical",
            "client_secret=synthetic-secret")
        .doesNotContain("username=", "password=", "refresh_token=");
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500})
  void rejectedBackendGrantNeverFallsBackToHumanPassword(int responseStatus) {
    status = responseStatus;
    body = "PRIVATE synthetic-secret identity reply";
    assertThatThrownBy(() -> client.loginService("backend-technical", "synthetic-secret"))
        .hasMessage("Backend identity authentication unavailable")
        .hasNoCause();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(form).doesNotContain("username=", "password=");
  }

  @ParameterizedTest
  @ValueSource(strings = {"secret", "client", "app"})
  void missingOrHumanClientCredentialsFailBeforeHttp(String missing) {
    assertThatThrownBy(
            () ->
                client.loginService(
                    "client".equals(missing)
                        ? ""
                        : "app".equals(missing) ? "app" : "backend-technical",
                    "secret".equals(missing) ? "" : "synthetic-secret"))
        .hasMessage("Backend identity client is not configured")
        .hasNoCause();
    assertThat(calls.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"access_token\":\"\",\"expires_in\":300}",
        "{\"access_token\":\"token\"}",
        "{\"access_token\":\"token\",\"expires_in\":0}"
      })
  void incompleteOrExpiredServiceResponseFailsSafely(String response) {
    body = response;
    assertThatThrownBy(() -> client.loginService("backend-technical", "synthetic-secret"))
        .hasMessage("Backend identity authentication unavailable")
        .hasNoCause();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void httpDiagnosticsRedactBackendSecret() {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RestTemplate.class);
    var previous = logger.getLevel();
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    try {
      client.loginService("backend-technical", "synthetic-secret");
      assertThat(appender.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .noneMatch(message -> message.contains("synthetic-secret"));
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
