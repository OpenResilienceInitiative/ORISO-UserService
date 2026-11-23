package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class BackendTechnicalHttpTest {
  @Test
  void agencyLookupUsesDedicatedClientCredentialsAndKeepsHumanLoginSeparate() throws Exception {
    var tokenForm = new AtomicReference<String>();
    var bearer = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          boolean token = exchange.getRequestURI().getPath().endsWith("/token");
          if (token)
            tokenForm.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          else bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body =
              (token
                      ? (tokenForm.get().contains("grant_type=client_credentials")
                          ? "{\"access_token\":\"technical-token\",\"expires_in\":300}"
                          : "{\"access_token\":\"technical-token\",\"expires_in\":300,\"refresh_expires_in\":0}")
                      : "{}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var config = new IdentityConfig();
      config.setOpenidConnectUrl(url);
      var technical = new TechnicalUserConfig();
      technical.setClientId("backend-technical");
      technical.setClientSecret("synthetic-client-secret");
      config.setTechnicalUser(technical);
      var authClient =
          new KeycloakAuthClient(new RestTemplate(), mock(AuthenticatedUser.class), config);
      ReflectionTestUtils.setField(authClient, "keycloakClientId", "app");
      IdentityAuthentication auth =
          new IdentityAuthentication() {
            public IdentityLogin loginService(String username, String password) {
              var response = authClient.loginService(username, password);
              return new IdentityLogin(response.getAccessToken(), response.getExpiresIn(), 0, null);
            }

            public IdentityLogin login(String username, String password) {
              throw new AssertionError("Technical caller must not use human login");
            }

            public boolean logout(String refreshToken) {
              throw new AssertionError("No service session logout");
            }

            public boolean verifyPasswordIgnoringSecondFactor(String username, String password) {
              return false;
            }
          };
      var headers = mock(SecurityHeaderSupplier.class);
      when(headers.getKeycloakAndCsrfHttpHeaders(anyString()))
          .thenAnswer(
              invocation -> {
                var value = new HttpHeaders();
                value.setBearerAuth(invocation.getArgument(0));
                return value;
              });
      var client =
          new AgencyMatrixCredentialClient(
              new RestTemplate(), headers, mock(TenantHeaderSupplier.class), auth, config);
      ReflectionTestUtils.setField(client, "agencyServiceBaseUrl", url);
      assertThat(client.fetchMatrixCredentials(1L)).isPresent();
      assertThat(bearer.get()).isEqualTo("Bearer technical-token");
      assertThat(tokenForm.get())
          .contains(
              "grant_type=client_credentials",
              "client_id=backend-technical",
              "client_secret=synthetic-client-secret")
          .doesNotContain("username=", "password=", "refresh_token=");
      authClient.loginUser("human", "human-password");
      assertThat(tokenForm.get())
          .contains(
              "grant_type=password", "client_id=app", "username=human", "password=human-password")
          .doesNotContain("client_secret=");
    } finally {
      server.stop(0);
    }
  }
}
