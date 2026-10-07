package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import de.caritas.cob.userservice.api.config.auth.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class KeycloakTaskCommandsTest {
  private static final byte[] CREATE_KEY = new byte[32];
  private static final byte[] MAINTAIN_KEY =
      "different-test-only-origin-key-32".getBytes(StandardCharsets.UTF_8);

  @Test
  void HTTPPasswordCommandBindsExactPayloadTargetAndDedicatedMaintenanceIdentity()
      throws Exception {
    var http = new RestTemplate();
    var server = MockRestServiceServer.bindTo(http).build();
    var config = configuration();
    var grants = mock(TaskIdentityGrant.class);
    when(grants.token(TaskIdentity.ACCOUNT_MAINTENANCE)).thenReturn("dedicated-maintenance-token");
    var proof = proof();
    var adapter =
        new KeycloakTaskCommands(http, config, grants, proof, "https://identity.example", "oriso");
    var origin =
        new IdentityCommandAuthorization(
            "SELF_SERVICE", "account.password", "own-account", null, List.of());

    server
        .expect(
            requestTo(
                "https://identity.example/realms/oriso/oriso-commands/v1/accounts/own-account/password"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer dedicated-maintenance-token"))
        .andExpect(
            request -> {
              try {
                var signed =
                    JWSObject.parse(request.getHeaders().getFirst("X-ORISO-Origin-Authorization"));
                assertThat(signed.verify(new MACVerifier(MAINTAIN_KEY))).isTrue();
                var claims = signed.getPayload().toJSONObject();
                assertThat(claims)
                    .containsEntry("taskClient", "backend-account-maintenance")
                    .containsEntry("taskSubject", "maintenance-subject")
                    .containsEntry("target", "own-account")
                    .containsEntry("tenantId", null)
                    .containsEntry("operation", "account.password");
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                assertThat(body)
                    .isEqualTo("{\"password\":\"chosen-secret\",\"passwordTemporary\":false}");
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(MAINTAIN_KEY, "HmacSHA256"));
                String expected =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                            mac.doFinal(("payload\n" + body).getBytes(StandardCharsets.UTF_8)));
                assertThat(claims.get("payloadDigest")).isEqualTo(expected);
                assertThat(signed.serialize()).doesNotContain("chosen-secret");
              } catch (Exception error) {
                throw new AssertionError(error);
              }
            })
        .andRespond(withNoContent());

    adapter.password("own-account", "chosen-secret", false, origin);
    server.verify();
    verify(grants).token(TaskIdentity.ACCOUNT_MAINTENANCE);
    verifyNoMoreInteractions(grants);
  }

  @Test
  void creatorUsesStringTenantWireAndSignsExactlyTheSentPayload() throws Exception {
    var http = new RestTemplate();
    var server = MockRestServiceServer.bindTo(http).build();
    var config = configuration();
    config
        .getTasks()
        .put(
            "account-provisioning",
            new TaskIdentityCredentials(
                "backend-account-provisioning", "unused-create-secret", "create-subject"));
    var grants = mock(TaskIdentityGrant.class);
    when(grants.token(TaskIdentity.ACCOUNT_PROVISIONING)).thenReturn("create-token");
    var adapter =
        new KeycloakTaskCommands(
            http, config, grants, proof(), "https://identity.example", "oriso");
    var attempt = UUID.randomUUID();
    var origin =
        new IdentityCommandAuthorization(
            "INVITATION", "account.create", attempt.toString(), "42", List.of("consultant"));
    server
        .expect(
            requestTo(
                "https://identity.example/realms/oriso/oriso-commands/v1/account-creations/"
                    + attempt))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            request -> {
              try {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                assertThat(json.path("tenantId").isTextual()).isTrue();
                assertThat(json.path("tenantId").asText()).isEqualTo("42");
                var signed =
                    JWSObject.parse(request.getHeaders().getFirst("X-ORISO-Origin-Authorization"));
                assertThat(signed.verify(new MACVerifier(CREATE_KEY))).isTrue();
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(CREATE_KEY, "HmacSHA256"));
                assertThat(signed.getPayload().toJSONObject().get("payloadDigest"))
                    .isEqualTo(
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                mac.doFinal(
                                    ("payload\n" + body).getBytes(StandardCharsets.UTF_8))));
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            })
        .andRespond(
            withSuccess(
                "{\"attemptId\":\""
                    + attempt
                    + "\",\"accountId\":\"new\",\"creationProof\":\"proof\",\"status\":\"OPEN\"}",
                MediaType.APPLICATION_JSON));
    adapter.create(
        attempt,
        new KeycloakTaskCommands.AccountCreation(
            "new",
            "new@example.invalid",
            "New",
            "User",
            null,
            42L,
            "fixture-secret",
            false,
            List.of("consultant"),
            "CONSULTANT"),
        origin);
    server.verify();
  }

  @Test
  void CapabilityForAnotherTargetOrActionCannotReachTransportOrObtainTaskGrant() {
    var grants = mock(TaskIdentityGrant.class);
    var adapter =
        new KeycloakTaskCommands(
            new RestTemplate(),
            configuration(),
            grants,
            proof(),
            "https://identity.example",
            "oriso");
    var wrongTarget =
        new IdentityCommandAuthorization(
            "SELF_SERVICE", "account.password", "other", null, List.of());
    assertThatThrownBy(() -> adapter.password("own", "secret", false, wrongTarget))
        .isInstanceOf(AccessDeniedException.class);
    var wrongAction =
        new IdentityCommandAuthorization("SELF_SERVICE", "account.read", "own", null, List.of());
    assertThatThrownBy(() -> adapter.password("own", "secret", false, wrongAction))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(grants);
  }

  private static TaskIdentityConfiguration configuration() {
    var config = new TaskIdentityConfiguration();
    config
        .getTasks()
        .put(
            "account-maintenance",
            new TaskIdentityCredentials(
                "backend-account-maintenance", "unused-secret", "maintenance-subject"));
    return config;
  }

  private static IdentityOriginProof proof() {
    return new IdentityOriginProof(
        Base64.getEncoder().encodeToString(CREATE_KEY),
        Base64.getEncoder().encodeToString(MAINTAIN_KEY),
        Clock.systemUTC());
  }
}
