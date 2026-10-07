package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.observability.OutboundHttpMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KeycloakConfigTest {

  private final KeycloakConfig keycloakConfig = new KeycloakConfig();
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
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
  void runtimeCannotConstructTheRetiredNativeAdministratorClient() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> keycloakConfig.keycloak(new OutboundHttpMetrics(new SimpleMeterRegistry())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(
            java.util.Arrays.stream(KeycloakConfig.class.getDeclaredMethods())
                .filter(
                    method ->
                        method.getReturnType().equals(org.keycloak.admin.client.Keycloak.class))
                .noneMatch(
                    method ->
                        method.isAnnotationPresent(
                            org.springframework.context.annotation.Bean.class)))
        .isTrue();
  }

  @Test
  void boundedRestTemplateStillMeasuresRequestsWithoutSensitiveTags() throws IOException {
    var registry = new SimpleMeterRegistry();
    startKeycloakStub();
    var http =
        keycloakConfig.keycloakRestTemplate(
            new org.springframework.boot.restclient.RestTemplateBuilder(),
            new OutboundHttpMetrics(registry));
    assertThat(
            http.getForObject(
                "http://localhost:"
                    + server.getAddress().getPort()
                    + "/realms/oriso/oriso-commands/v1/accounts/account",
                String.class))
        .isEqualTo("0");
    assertThat(
            registry
                .get("userservice.outbound.http.calls")
                .tags("dependency", "localhost", "method", "get", "outcome", "2xx")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags().toString())
                    .doesNotContain("account", "oriso", "password"));
  }

  private void startKeycloakStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          var responseBody =
              exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")
                  ? """
                    {"access_token":"token","expires_in":300,"refresh_expires_in":1800,
                     "refresh_token":"refresh","token_type":"Bearer","session_state":"session"}
                    """
                  : "0";
          var responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, responseBytes.length);
          exchange.getResponseBody().write(responseBytes);
          exchange.close();
        });
    server.start();
  }

  private void configureKeycloak() {
    keycloakConfig.setAuthServerUrl("http://localhost:" + server.getAddress().getPort());
    keycloakConfig.setRealm("oriso");
    var customConfig = new KeycloakCustomConfig();

    customConfig.setAdminClientSecret("password");
    customConfig.setAdminClientId("admin-cli");
    keycloakConfig.setConfig(customConfig);
  }
}
