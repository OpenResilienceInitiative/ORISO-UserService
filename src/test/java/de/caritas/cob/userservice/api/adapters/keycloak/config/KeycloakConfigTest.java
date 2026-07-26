package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.CONNECT_TIMEOUT;
import static de.caritas.cob.userservice.api.config.RestTemplateTimeouts.READ_TIMEOUT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.JacksonProvider;

class KeycloakConfigTest {

  private final KeycloakConfig keycloakConfig = new KeycloakConfig();
  private HttpServer httpServer;

  @AfterEach
  void stopServer() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  @Test
  void resolveUsernameClaim_ShouldPreferMappedUsernameClaim_WhenPresent() {
    var usernameClaim =
        keycloakConfig.resolveUsernameClaim(Map.of("username", "mapped"), "preferred");

    assertThat(usernameClaim).isEqualTo("mapped");
  }

  @Test
  void resolveUsernameClaim_ShouldFallbackToPreferredUsername_WhenMappedClaimIsMissing() {
    var usernameClaim = keycloakConfig.resolveUsernameClaim(Map.of(), "preferred");

    assertThat(usernameClaim).isEqualTo("preferred");
  }

  @Test
  void resolveUsernameClaim_ShouldUseFirstTextValue_WhenMappedClaimIsCollection() {
    var usernameClaim =
        keycloakConfig.resolveUsernameClaim(Map.of("username", List.of("", "mapped")), "preferred");

    assertThat(usernameClaim).isEqualTo("mapped");
  }

  @Test
  void resolveUsernameClaim_ShouldFallbackToPreferredUsername_WhenMappedClaimIsBlank() {
    var usernameClaim = keycloakConfig.resolveUsernameClaim(Map.of("username", ""), "preferred");

    assertThat(usernameClaim).isEqualTo("preferred");
  }

  @Test
  void resolveTenantIdClaim_ShouldUseFirstTextValue_WhenMappedClaimIsCollection() {
    var tenantIdClaim = keycloakConfig.resolveTenantIdClaim(Map.of("tenantId", List.of("", "7")));

    assertThat(tenantIdClaim).isEqualTo("7");
  }

  @Test
  void keycloakAdminHttpClientBuilder_ShouldUseBoundedTimeoutsAndKeycloakJsonProvider() {
    var metrics = new OutboundHttpMetrics(new SimpleMeterRegistry());
    var builder = keycloakConfig.keycloakAdminHttpClientBuilder(metrics);

    assertThat(builder.getConnectionTimeout(MILLISECONDS)).isEqualTo(CONNECT_TIMEOUT.toMillis());
    assertThat(builder.getReadTimeout(MILLISECONDS)).isEqualTo(READ_TIMEOUT.toMillis());
    assertThat(builder.getConfiguration().isRegistered(JacksonProvider.class)).isTrue();
  }

  @Test
  void keycloakAdminHttpClientBuilder_ShouldMeasureSerializedPayloadAndBoundMetricTags()
      throws Exception {
    var requestBody = "request-body";
    var responseBody = "response-body";
    startServer(201, responseBody);
    var registry = new SimpleMeterRegistry();
    var metrics = new OutboundHttpMetrics(registry);

    try (var client = keycloakConfig.keycloakAdminHttpClientBuilder(metrics).build();
        var response =
            client
                .target(serverBaseUrl() + "/admin/realms/oriso/users?username=secret")
                .request()
                .post(Entity.text(requestBody))) {
      assertThat(response.getStatus()).isEqualTo(201);
    }

    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", loopbackHost(), "method", "post", "outcome", "2xx")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.outbound.http.payload")
                .tags("dependency", loopbackHost(), "direction", "request")
                .summary()
                .totalAmount())
        .isEqualTo(requestBody.getBytes(StandardCharsets.UTF_8).length);
    assertThat(
            registry
                .get("userservice.outbound.http.payload")
                .tags("dependency", loopbackHost(), "direction", "response")
                .summary()
                .totalAmount())
        .isEqualTo(responseBody.getBytes(StandardCharsets.UTF_8).length);
    assertThat(
            registry
                .get("userservice.outbound.http.latency")
                .tags("dependency", loopbackHost(), "method", "post", "outcome", "2xx")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(registry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags().toString())
                    .doesNotContain("admin", "realms", "oriso", "users", "username", "secret"));
  }

  @Test
  void keycloakAdminHttpClientBuilder_ShouldMeasureTransportErrors() throws Exception {
    startServer(200, "");
    var targetUrl = serverBaseUrl() + "/admin/realms/oriso/users";
    httpServer.stop(0);
    httpServer = null;
    var registry = new SimpleMeterRegistry();
    var metrics = new OutboundHttpMetrics(registry);

    try (var client = keycloakConfig.keycloakAdminHttpClientBuilder(metrics).build()) {
      assertThatThrownBy(() -> client.target(targetUrl).request().get())
          .isInstanceOf(ProcessingException.class);
    }

    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", loopbackHost(), "method", "get", "outcome", "io_error")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("userservice.outbound.http.latency")
                .tags("dependency", loopbackHost(), "method", "get", "outcome", "io_error")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void keycloakAdminHttpClientBuilder_ShouldNotInferUnknownResponsePayloadSize() throws Exception {
    startChunkedServer("chunked-response");
    var registry = new SimpleMeterRegistry();
    var metrics = new OutboundHttpMetrics(registry);

    try (var client = keycloakConfig.keycloakAdminHttpClientBuilder(metrics).build();
        var response =
            client.target(serverBaseUrl() + "/admin/realms/oriso/users").request().get()) {
      assertThat(response.readEntity(String.class)).isEqualTo("chunked-response");
    }

    assertThat(
            registry
                .find("userservice.outbound.http.payload")
                .tags("dependency", loopbackHost(), "direction", "response")
                .summary())
        .isNull();
  }

  private void startServer(int status, String responseBody) throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    httpServer.createContext(
        "/admin/realms/oriso/users",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    httpServer.start();
  }

  private void startChunkedServer(String responseBody) throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    httpServer.createContext(
        "/admin/realms/oriso/users",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().write(responseBody.getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    httpServer.start();
  }

  private String serverBaseUrl() {
    var host = loopbackHost();
    var uriHost = host.contains(":") ? "[" + host + "]" : host;
    return "http://" + uriHost + ":" + httpServer.getAddress().getPort();
  }

  private String loopbackHost() {
    return InetAddress.getLoopbackAddress().getHostAddress();
  }
}
