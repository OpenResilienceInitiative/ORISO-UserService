package de.caritas.cob.userservice.api.adapters.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.web.client.RestTemplate;

class KeycloakGuestIdentityAdapterTest {
  private static final String USERS = "/admin/realms/oriso/users";
  private static final String USERNAME = "biene_rayan_1234";
  private static final String MARKER = "guest-join-42-candidate-1";
  private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
  private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
  private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
  private HttpServer server;
  private Keycloak keycloak;
  private KeycloakGuestIdentityAdapter adapter;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            Reply reply = replies.remove();
            assertThat(exchange.getRequestMethod()).isEqualTo(reply.method());
            assertThat(exchange.getRequestURI().getPath()).isEqualTo(reply.path());
            if (exchange.getRequestURI().getQuery() != null) {
              assertThat(exchange.getRequestURI().getQuery())
                  .contains("exact=true", "username=" + USERNAME);
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) bodies.add(new ObjectMapper().readTree(body));
            if (reply.status() == 201)
              exchange.getResponseHeaders().set("Location", url() + USERS + "/owned-id");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), response.length);
            exchange.getResponseBody().write(response);
          } catch (Throwable failure) {
            handlerFailure.set(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    keycloak =
        KeycloakBuilder.builder()
            .serverUrl(url())
            .realm("oriso")
            .authorization("test-admin-token")
            .build();
    var config = new KeycloakConfig();
    config.setRealm("oriso");
    var identityConfig =
        org.mockito.Mockito.mock(
            de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class);
    org.mockito.Mockito.when(identityConfig.getEmailDummySuffix()).thenReturn("@example.test");
    adapter =
        new KeycloakGuestIdentityAdapter(
            new KeycloakClient(new RestTemplate(), keycloak, config),
            new de.caritas.cob.userservice.api.helper.UserHelper(
                new de.caritas.cob.userservice.api.helper.UsernameTranscoder(), identityConfig));
  }

  @AfterEach
  void stop() {
    if (keycloak != null) keycloak.close();
    if (server != null) server.stop(0);
    assertThat(handlerFailure.get()).isNull();
    assertThat(replies).isEmpty();
  }

  @Test
  void deletesOnlyTheOwnedProvisionalAccountAndRequiresAbsentReadback() {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    replies.add(new Reply("DELETE", USERS + "/owned-id", 204, ""));
    replies.add(new Reply("GET", USERS + "/owned-id", 404, "{}"));
    adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER);
  }

  @Test
  void cleanupReplayOfAnAbsentAccountDoesNotDeleteAnything() {
    replies.add(new Reply("GET", USERS + "/owned-id", 404, "{}"));
    adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER);
  }

  @Test
  void lostDeleteResponseIsUnknownUntilRetryConfirmsAbsence() {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    replies.add(new Reply("DELETE", USERS + "/owned-id", 503, "{}"));
    assertThatThrownBy(() -> adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    replies.add(new Reply("GET", USERS + "/owned-id", 404, "{}"));
    adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER);
  }

  @Test
  void successfulDeleteResponseDoesNotProveTheAccountIsGone() {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    replies.add(new Reply("DELETE", USERS + "/owned-id", 204, ""));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    assertThatThrownBy(() -> adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void cleanupCannotDeleteForeignOrDisabledAccounts() {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned("someone-else", true)));
    assertThatThrownBy(() -> adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ConflictException.class);
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, false)));
    assertThatThrownBy(() -> adapter.deleteOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void markerAndPermanentPasswordAreIncludedInInitialCreate() {
    replies.add(new Reply("POST", USERS, 201, "{}"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));

    assertThat(adapter.createOnly(USERNAME, "stable-password", 7L, MARKER)).isEqualTo("owned-id");
    JsonNode create = bodies.getFirst();
    assertThat(create.path("username").asText()).isEqualTo(USERNAME);
    assertThat(create.at("/attributes/oriso_guest_join_attempt/0").asText()).isEqualTo(MARKER);
    assertThat(create.at("/attributes/tenantId/0").asText()).isEqualTo("7");
    assertThat(create.at("/credentials/0/value").asText()).isEqualTo("stable-password");
    assertThat(create.at("/credentials/0/temporary").asBoolean()).isFalse();
    assertThat(create.at("/credentials/0/type").asText()).isEqualTo("password");
  }

  @Test
  void retryFindsOwnedAccountWithoutResettingPassword() {
    replies.add(
        new Reply("GET", USERS, 200, "[{\"id\":\"owned-id\",\"username\":\"" + USERNAME + "\"}]"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    assertThat(adapter.findOwned(USERNAME, 7L, MARKER)).contains("owned-id");
    assertThat(bodies).isEmpty();
  }

  @Test
  void occupiedUsernameDoesNotPermitTakingOverAnotherAttempt() {
    replies.add(
        new Reply("GET", USERS, 200, "[{\"id\":\"owned-id\",\"username\":\"" + USERNAME + "\"}]"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned("another-attempt", true)));
    assertThatThrownBy(() -> adapter.findOwned(USERNAME, 7L, MARKER))
        .isInstanceOf(ConflictException.class);
    assertThat(bodies).isEmpty();
  }

  @Test
  void lostOrStrippedMarkerAfterCreateIsUnknownNotPermissionToReplace() {
    replies.add(new Reply("POST", USERS, 201, "{}"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned("", true)));
    assertThatThrownBy(() -> adapter.createOnly(USERNAME, "stable-password", 7L, MARKER))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void disabledOwnedAccountIsNeverReenabled() {
    replies.add(
        new Reply("GET", USERS, 200, "[{\"id\":\"owned-id\",\"username\":\"" + USERNAME + "\"}]"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, false)));
    assertThatThrownBy(() -> adapter.findOwned(USERNAME, 7L, MARKER))
        .isInstanceOf(ForbiddenException.class);
    assertThat(bodies).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void requiresVerifiedTechnicalEmailReadbackAndPreservesOwnedProfile(boolean verified) {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    replies.add(new Reply("PUT", USERS + "/owned-id", 200, "{}"));
    replies.add(
        new Reply(
            "GET",
            "/admin/realms/oriso/roles/user",
            200,
            "{\"id\":\"role-user\",\"name\":\"user\"}"));
    replies.add(new Reply("POST", USERS + "/owned-id/role-mappings/realm", 200, "{}"));
    replies.add(
        new Reply(
            "GET",
            USERS + "/owned-id/role-mappings/realm",
            200,
            "[{\"id\":\"role-user\",\"name\":\"user\"}]"));
    replies.add(
        new Reply(
            "GET",
            USERS + "/owned-id",
            200,
            completed().replace("\"emailVerified\":true", "\"emailVerified\":" + verified)));

    if (verified) {
      adapter.completeOwned("owned-id", USERNAME, 7L, MARKER);
    } else {
      assertThatThrownBy(() -> adapter.completeOwned("owned-id", USERNAME, 7L, MARKER))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasNoCause();
    }
    JsonNode patch = bodies.getFirst();
    assertThat(patch.path("username").asText()).isEqualTo(USERNAME);
    assertThat(patch.path("email").asText()).isEqualTo("owned-id@example.test");
    assertThat(patch.path("emailVerified").asBoolean()).isTrue();
    assertThat(patch.at("/attributes/userId/0").asText()).isEqualTo("owned-id");
    assertThat(patch.at("/attributes/oriso_guest_join_attempt/0").asText()).isEqualTo(MARKER);
    assertThat(patch.has("enabled")).isFalse();
    assertThat(patch.has("credentials")).isFalse();
    assertThat(bodies.get(1).get(0).path("name").asText()).isEqualTo("user");
  }

  @Test
  void completionCannotChangeForeignIdentityAttributesOrRoles() {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned("someone-else", true)));
    assertThatThrownBy(() -> adapter.completeOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ConflictException.class);
    assertThat(bodies).isEmpty();
  }

  @Test
  void strippedMarkerStaysUnknownOnRetryAfterSuccessfulCreate() {
    replies.add(new Reply("POST", USERS, 201, "{}"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned("", true)));
    replies.add(
        new Reply("GET", USERS, 200, "[{\"id\":\"owned-id\",\"username\":\"" + USERNAME + "\"}]"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned("", true)));
    assertThatThrownBy(() -> adapter.createOnly(USERNAME, "stable-password", 7L, MARKER))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThatThrownBy(() -> adapter.findOwned(USERNAME, 7L, MARKER))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void completionNeverOverwritesAnUnexpectedExistingEmail() {
    replies.add(
        new Reply(
            "GET",
            USERS + "/owned-id",
            200,
            owned(MARKER, true)
                .replace("\"attributes\":", "\"email\":\"changed@example.test\",\"attributes\":")));
    assertThatThrownBy(() -> adapter.completeOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ForbiddenException.class);
    assertThat(bodies).isEmpty();
  }

  @Test
  void lostProfileUpdateResponseCanCompleteTheSameIdentityOnRetry() {
    replies.add(new Reply("GET", USERS + "/owned-id", 200, owned(MARKER, true)));
    replies.add(new Reply("PUT", USERS + "/owned-id", 503, "{}"));
    assertThatThrownBy(() -> adapter.completeOwned("owned-id", USERNAME, 7L, MARKER))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasNoCause();
    replies.add(new Reply("GET", USERS + "/owned-id", 200, completed()));
    replies.add(new Reply("PUT", USERS + "/owned-id", 200, "{}"));
    replies.add(
        new Reply(
            "GET",
            "/admin/realms/oriso/roles/user",
            200,
            "{\"id\":\"role-user\",\"name\":\"user\"}"));
    replies.add(new Reply("POST", USERS + "/owned-id/role-mappings/realm", 200, "{}"));
    replies.add(
        new Reply(
            "GET",
            USERS + "/owned-id/role-mappings/realm",
            200,
            "[{\"id\":\"role-user\",\"name\":\"user\"}]"));
    replies.add(new Reply("GET", USERS + "/owned-id", 200, completed()));
    adapter.completeOwned("owned-id", USERNAME, 7L, MARKER);
    assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
    assertThat(bodies.get(1).has("credentials")).isFalse();
  }

  private String completed() {
    return owned(MARKER, true)
        .replace(
            "\"attributes\":",
            "\"email\":\"owned-id@example.test\",\"emailVerified\":true,\"attributes\":")
        .replace("\"tenantId\"", "\"userId\":[\"owned-id\"],\"tenantId\"");
  }

  private String owned(String marker, boolean enabled) {
    return "{\"id\":\"owned-id\",\"username\":\""
        + USERNAME
        + "\",\"enabled\":"
        + enabled
        + ",\"attributes\":{\"tenantId\":[\"7\"],\"oriso_guest_join_attempt\":[\""
        + marker
        + "\"]}}";
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private record Reply(String method, String path, int status, String body) {}
}
