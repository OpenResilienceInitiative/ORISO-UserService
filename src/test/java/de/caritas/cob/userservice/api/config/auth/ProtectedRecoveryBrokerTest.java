package de.caritas.cob.userservice.api.config.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountRecovery;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Real broker + signed HTTP/JWKS issuer; opaque native handles remain explicit test doubles. */
class ProtectedRecoveryBrokerTest {
  @Test
  void recoveryUsesTheRealCheckedAdminIssuerWhileGenericIssuanceStaysClosed() throws Exception {
    try (var issuer = new SyntheticTaskIssuer()) {
      issuer.adminSubject = "091b8cef-09a8-4e5b-a244-6be3db4b7bee";
      var environment = FiveActorConfigurationTest.environment();
      environment.setProperty("IDENTITY_ACCOUNT_ADMIN_SERVICE_SUBJECT", issuer.adminSubject);
      var actors = FiveActorConfigurationTest.configured(environment);
      var identity = new IdentityConfig();
      identity.setOpenidConnectUrl(issuer.base());
      var authentication =
          new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), identity);
      ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
      var keycloak = new KeycloakConfig();
      keycloak.setAuthServerUrl(issuer.base());
      keycloak.setRealm("synthetic");
      var decoder = NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs").build();
      var broker = new TaskActorTokenBroker(actors, authentication, decoder, keycloak);
      var checked = broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
      assertEquals(issuer.adminSubject, checked.subject());
      assertEquals(1, issuer.calls.get());
      assertThrows(
          IllegalStateException.class, () -> broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE));
      assertEquals(1, issuer.calls.get());
      byte[] key = new byte[32];
      var clock = java.time.Clock.systemUTC();
      var approvals =
          new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec(
              key, clock, "synthetic", "synthetic-realm");
      var codec =
          spy(new ProtectedAccountCodec(
              key, clock, approvals, "synthetic", "synthetic-realm", issuer.issuer()));
      var prepared = mock(ProtectedAccountCodec.PreparedRecovery.class);
      var recovered = mock(ProtectedAccountCodec.VerifiedRecovery.class);
      UUID approval = UUID.fromString("7f56c348-9785-4c78-b905-3d008dd42482");
      UUID attempt = UUID.fromString("39fe99aa-fd8e-44c8-9b48-71ea6cc09d38");
      byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
      when(prepared.approvalId()).thenReturn(approval);
      when(prepared.attemptId()).thenReturn(attempt);
      doReturn(response).when(codec).recoverBody(prepared);
      doReturn("synthetic-proof").when(codec).recoverProof(eq(prepared), any());
      doReturn(recovered).when(codec).verifyRecovery(eq(prepared), any());
      var http = new RestTemplate();
      var server = MockRestServiceServer.bindTo(http).build();
      server
          .expect(
              requestTo(
                  "https://receiver.example.test/realms/synthetic/oriso-protected-invitations/v1/approvals/"
                      + approval
                      + "/attempts/"
                      + attempt
                      + "/recover"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("X-ORISO-Task-Context", "synthetic-proof"))
          .andExpect(
              request -> {
                String bearer = request.getHeaders().getFirst("Authorization");
                assertNotNull(bearer);
                assertTrue(bearer.startsWith("Bearer "));
                var actual = decoder.decode(bearer.substring(7));
                assertEquals(issuer.adminSubject, actual.getSubject());
                assertEquals("backend-account-admin", actual.getClaimAsString("azp"));
              })
          .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
      var transport =
          new ProtectedAccountRecovery(
              http, actors, broker, codec, "https://receiver.example.test", "synthetic");
      assertSame(recovered, assertDoesNotThrow(() -> transport.recover(prepared)));
      assertEquals(2, issuer.calls.get());
      server.verify();
      assertThrows(
          IllegalStateException.class, () -> broker.issue(TaskPurpose.ACCOUNT_MAINTENANCE));
      assertEquals(2, issuer.calls.get());
    }
  }
}
