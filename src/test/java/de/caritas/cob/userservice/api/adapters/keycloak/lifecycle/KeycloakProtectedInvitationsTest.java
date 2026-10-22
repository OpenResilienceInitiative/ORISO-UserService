package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

class KeycloakProtectedInvitationsTest {
  private final JsonNode vector = vector();
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final ProtectedInvitationCodec codec =
      new ProtectedInvitationCodec(
          HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText()),
          Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
          "synthetic",
          vector.path("issuerRealm").asText());
  private final KeycloakProtectedInvitations transport = transport();

  @Test
  void approvesOnlyWithTheImmediateHumanBearerAndTheExactFrozenUtf8Body() {
    JsonNode utf8 = vector("/scenario-c/protected-approval-utf8-v1.json");
    var frozen =
        codec.freeze(
            UUID.fromString(utf8.path("approvalId").asText()),
            utf8.path("request").toString(),
            utf8.path("issuerRealm").asText(),
            utf8.path("issuerSubject").asText());
    assertTrue(frozen.requestJson().contains("äda@example.test"));
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header("Authorization", "Bearer public-synthetic-human"))
        .andExpect(headerDoesNotExist("X-ORISO-Task-Context"))
        .andExpect(content().bytes(frozen.requestJson().getBytes(StandardCharsets.UTF_8)))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(utf8.path("response").toString()));
    var ready = transport.approve(frozen, "public-synthetic-human");
    assertNotNull(ready);
    assertEquals(frozen.approvalId(), ready.approvalId());
    server.verify();
    verifyNoInteractions(actors, broker);
  }

  @Test
  void aLostApprovalReplyReconcilesTheSameIdAfterReconstructionWithoutReplayingTheHuman() {
    var frozen = frozen();
    setupActor();
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(
            request -> {
              throw new IOException("synthetic lost reply");
            });
    server
        .expect(requestTo(url() + "/status"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header("Authorization", "Bearer public-synthetic-setup"))
        .andExpect(
            request ->
                assertStatusProof(request.getHeaders().getFirst("X-ORISO-Task-Context"), frozen))
        .andExpect(content().bytes(codec.statusBody(frozen).getBytes(StandardCharsets.UTF_8)))
        .andRespond(withSuccess(response(), MediaType.APPLICATION_JSON));
    var lost =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class,
            () -> transport.approve(frozen, "public-synthetic-human"));
    assertEquals(0, lost.status());
    verifyNoInteractions(actors, broker);
    var ready = transport().reconcile(frozen);
    assertNotNull(ready);
    assertEquals(frozen.approvalId(), ready.approvalId());
    server.verify();
    verify(broker).issue(TaskPurpose.INVITE_RESERVATIONS);
    verifyNoMoreInteractions(broker);
  }

  @Test
  void missingHumanAuthorityNeverFallsBackToAMachineOrSendsHttp() {
    for (String token : new String[] {null, "", "  ", "invalid\r\nheader"}) {
      assertThrows(IllegalArgumentException.class, () -> transport.approve(frozen(), token));
    }
    server.verify();
    verifyNoInteractions(actors, broker);
  }

  @Test
  void aConfirmedStatus404NeverRetriesApprovalOrBorrowsAHumanCredential() {
    setupActor();
    server
        .expect(requestTo(url() + "/status"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    var missing =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class, () -> transport.reconcile(frozen()));
    assertEquals(404, missing.status());
    server.verify();
    verify(broker).issue(TaskPurpose.INVITE_RESERVATIONS);
    verifyNoMoreInteractions(broker);
  }

  @Test
  void a200ReplyWithAForeignApprovalCannotBecomeReady() {
    var changed = (ObjectNode) vector.path("response").deepCopy();
    changed.put("approvalId", UUID.randomUUID().toString());
    server
        .expect(requestTo(url()))
        .andRespond(withSuccess(changed.toString(), MediaType.APPLICATION_JSON));
    var failure =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class,
            () -> transport.approve(frozen(), "public-synthetic-human"));
    assertEquals(0, failure.status());
    server.verify();
    verifyNoInteractions(actors, broker);
  }

  @Test
  void providerBodiesAndBearerMaterialNeverEscapeInTransportErrors() {
    String syntheticBody = "private-synthetic-provider-profile";
    server
        .expect(requestTo(url()))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).body(syntheticBody));
    var failure =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class,
            () -> transport.approve(frozen(), "public-synthetic-human"));
    assertEquals(403, failure.status());
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains(syntheticBody));
    assertFalse(failure.getMessage().contains("public-synthetic-human"));
    server.verify();
  }

  @Test
  void invalidUtf8IsAnUnknownOutcomeRatherThanALocalApproval() {
    server
        .expect(requestTo(url()))
        .andRespond(withSuccess(new byte[] {(byte) 0xc3, (byte) 0x28}, MediaType.APPLICATION_JSON));
    var failure =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class,
            () -> transport.approve(frozen(), "public-synthetic-human"));
    assertEquals(0, failure.status());
    server.verify();
  }

  @Test
  void anAccepted202IsNotACompletedNativeAuthorization() {
    server
        .expect(requestTo(url()))
        .andRespond(
            withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response()));
    var failure =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class,
            () -> transport.approve(frozen(), "public-synthetic-human"));
    assertEquals(0, failure.status());
    server.verify();
  }

  @Test
  void aChangedSavedProposalNeverReachesTheReceiver() {
    var original = frozen();
    var forged =
        new ProtectedInvitationCodec.FrozenApproval(
            original.approvalId(),
            original.requestJson(),
            original.issuerRealm(),
            original.issuerSubject(),
            "0".repeat(64));
    assertThrows(
        IllegalArgumentException.class, () -> transport.approve(forged, "public-synthetic-human"));
    assertThrows(IllegalArgumentException.class, () -> transport.reconcile(forged));
    server.verify();
    verifyNoInteractions(actors, broker);
  }

  @Test
  void anOpenLocalSqlTransactionCannotContainAReceiverCall() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(
          IllegalStateException.class, () -> transport.approve(frozen(), "public-synthetic-human"));
      assertThrows(IllegalStateException.class, () -> transport.reconcile(frozen()));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    server.verify();
    verifyNoInteractions(actors, broker);
  }

  private ProtectedInvitationCodec.FrozenApproval frozen() {
    return codec.freeze(
        UUID.fromString(vector.path("approvalId").asText()),
        vector.path("request").toString(),
        vector.path("issuerRealm").asText(),
        vector.path("issuerSubject").asText());
  }

  private KeycloakProtectedInvitations transport() {
    return new KeycloakProtectedInvitations(
        http, actors, broker, codec, "https://identity.example.test", "synthetic");
  }

  private String url() {
    return "https://identity.example.test/realms/synthetic/oriso-protected-invitations/v1/approvals/"
        + vector.path("approvalId").asText();
  }

  private String response() {
    return vector.path("response").toString();
  }

  private void setupActor() {
    lenient()
        .when(actors.require(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.SETUP,
                "backend-setup",
                "public-synthetic-secret",
                "4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed"));
    lenient()
        .when(broker.issue(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.SETUP, "public-synthetic-setup", 60));
  }

  private static JsonNode vector() {
    return vector("/scenario-c/protected-approval-v1.json");
  }

  private static JsonNode vector(String resource) {
    try (var input = KeycloakProtectedInvitationsTest.class.getResourceAsStream(resource)) {
      assertNotNull(input);
      return new ObjectMapper().readTree(input);
    } catch (IOException failure) {
      throw new AssertionError("Synthetic wire vector is unreadable", failure);
    }
  }

  private void assertStatusProof(String proof, ProtectedInvitationCodec.FrozenApproval frozen)
      throws IOException {
    assertNotNull(proof);
    String[] parts = proof.split("\\.");
    assertEquals(2, parts.length);
    JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
    assertEquals(15, claims.size());
    assertEquals("userservice", claims.path("iss").asText());
    assertEquals("oriso-protected-invitations", claims.path("aud").asText());
    assertEquals("synthetic", claims.path("realm").asText());
    assertEquals("INVITE_RESERVATIONS", claims.path("purpose").asText());
    assertEquals("SETUP", claims.path("actor").asText());
    assertEquals("protected-invitation.status", claims.path("operation").asText());
    assertEquals("POST", claims.path("method").asText());
    assertEquals("backend-setup", claims.path("taskClient").asText());
    assertEquals("4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed", claims.path("taskSubject").asText());
    assertEquals(
        "realm/"
            + vector.path("issuerRealm").asText()
            + "/protected-invitation/"
            + frozen.approvalId(),
        claims.path("target").asText());
    assertEquals(
        Instant.parse("2030-01-01T00:00:00Z").getEpochSecond(), claims.path("iat").asLong());
    assertEquals(claims.path("iat").asLong() + 60, claims.path("exp").asLong());
    assertEquals(
        claims.path("jti").asText(), UUID.fromString(claims.path("jti").asText()).toString());
    assertTrue(claims.path("nonce").asText().matches("[0-9a-f]{32}"));
    try {
      byte[] key = HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText());
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      assertEquals(
          HexFormat.of()
              .formatHex(
                  mac.doFinal(
                      ("protected-invitation-payload/v1\n" + codec.statusBody(frozen))
                          .getBytes(StandardCharsets.UTF_8))),
          claims.path("payloadDigest").asText());
      assertEquals(
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  mac.doFinal(
                      ("protected-invitation-proof/v1\n" + parts[0])
                          .getBytes(StandardCharsets.UTF_8))),
          parts[1]);
    } catch (java.security.GeneralSecurityException failure) {
      throw new AssertionError("Synthetic proof HMAC unavailable", failure);
    }
  }
}
