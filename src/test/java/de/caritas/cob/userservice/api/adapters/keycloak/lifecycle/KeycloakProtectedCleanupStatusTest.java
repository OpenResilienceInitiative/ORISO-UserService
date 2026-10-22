package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.config.auth.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

/**
 * Real native MAC vectors and fixed HTTP boundary; receiver and token issuance are explicit
 * doubles.
 */
class KeycloakProtectedCleanupStatusTest {
  private final byte[] key =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final JsonNode account = resource("protected-account-v1.json");
  private final JsonNode vector = resource("protected-cleanup-status-v1.json");
  private final JsonNode recoveryVector = resource("protected-account-recovery-v1.json");
  private final Clock clock = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final ProtectedInvitationCodec approvals =
      new ProtectedInvitationCodec(
          key, clock, account.path("realmName").asText(), account.path("realmId").asText());
  private final ProtectedAccountCodec accounts =
      new ProtectedAccountCodec(
          key,
          clock,
          approvals,
          account.path("realmName").asText(),
          account.path("realmId").asText(),
          account.path("realmIssuer").asText());
  private final ProtectedInvitationRestrictionCodec restrictions =
      new ProtectedInvitationRestrictionCodec(
          approvals,
          json,
          key,
          clock,
          account.path("realmId").asText(),
          account.path("realmIssuer").asText());
  private final ProtectedCleanupStatusCodec cleanup =
      new ProtectedCleanupStatusCodec(json, restrictions, accounts, key);
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private TaskActorCredentials setup;

  @BeforeEach
  void actualTuple() {
    JsonNode scope = account.path("approvalVector").path("request").path("scope");
    setup =
        new TaskActorCredentials(
            TaskActor.SETUP,
            scope.path("setupClient").asText(),
            "synthetic-only",
            scope.path("setupSubject").asText());
    lenient().when(actors.require(TaskPurpose.INVITE_RESERVATIONS)).thenReturn(setup);
    lenient()
        .when(broker.issue(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorTokenBroker.TaskToken(TaskActor.SETUP, "public-synthetic-setup", 60));
  }

  @Test
  void exactIndependentGetVerifiesCurrentCompensationAndEveryFixedProofClaim() {
    var frozen = frozen();
    var original = restrictions.verify(frozen, bytes(vector.path("boundRestriction")), true);
    server
        .expect(requestTo(url()))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer public-synthetic-setup"))
        .andExpect(header("Cache-Control", "no-store"))
        .andExpect(content().bytes(new byte[0]))
        .andExpect(request -> assertProof(request.getHeaders().getFirst("X-ORISO-Task-Context")))
        .andRespond(withSuccess(json.canonical(vector.path("bound")), MediaType.APPLICATION_JSON));
    var result =
        assertDoesNotThrow(
            () -> transport(true).cleanupStatus(frozen, original, recovery(), local()));
    assertNotNull(result);
    assertEquals(vector.path("bound"), json.read(result.responseJson(), JsonNode.class));
    assertTrue(original.cleanupRequired());
    server.verify();
    verify(broker).issue(TaskPurpose.INVITE_RESERVATIONS);
    verify(broker, never()).issueCheckedMaintenance(any());
  }

  @Test
  void oldAbandonReceiptAndUnknownOrForeignWrapperCannotBecomeIndependentCleanupAcknowledgement() {
    var frozen = frozen();
    var original = restrictions.verify(frozen, bytes(vector.path("boundRestriction")), true);
    server
        .expect(requestTo(url()))
        .andRespond(
            withSuccess(
                json.canonical(recoveryVector.path("abandonedResponse")),
                MediaType.APPLICATION_JSON));
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () -> transport(true).cleanupStatus(frozen, original, recovery(), local()));
    server.verify();
    server.reset();
    server.expect(requestTo(url())).andRespond(withStatus(HttpStatus.CONFLICT));
    var failed =
        assertThrows(
            KeycloakProtectedInvitations.RemoteFailure.class,
            () -> transport(true).cleanupStatus(frozen, original, recovery(), local()));
    assertEquals(409, failed.status());
    assertFalse(failed.toString().contains("public-synthetic"));
    server.verify();
    server.reset();
    server
        .expect(requestTo(url()))
        .andRespond(withSuccess(json.canonical(vector.path("bound")), MediaType.APPLICATION_JSON));
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () ->
            transport(true)
                .cleanupStatus(
                    frozen,
                    original,
                    recovery(),
                    new LocalOutcome(1, UUID.randomUUID(), "a".repeat(64))));
    server.verify();
  }

  @Test
  void missingVerifierWrongSetupAndAmbientOwnerRefuseBeforeAnyNetworkOrBearerIssuance() {
    var frozen = frozen();
    var original = restrictions.verify(frozen, bytes(vector.path("boundRestriction")), true);
    var recovered = recovery();
    assertThrows(
        IllegalArgumentException.class,
        () -> transport(false).cleanupStatus(frozen, original, recovered, local()));
    when(actors.require(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.SETUP, setup.clientId(), "synthetic-only", UUID.randomUUID().toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> transport(true).cleanupStatus(frozen, original, recovered, local()));
    verifyNoInteractions(broker);
    when(actors.require(TaskPurpose.INVITE_RESERVATIONS)).thenReturn(setup);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThrows(
          IllegalStateException.class,
          () -> transport(true).cleanupStatus(frozen, original, recovered, local()));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(broker);
    server.verify();
  }

  private void assertProof(String proof) {
    assertNotNull(proof);
    String[] pieces = proof.split("\\.", -1);
    assertEquals(2, pieces.length);
    JsonNode value =
        json.read(
            new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8),
            JsonNode.class);
    var names = new HashSet<String>();
    value.fieldNames().forEachRemaining(names::add);
    assertEquals(
        Set.of(
            "iss",
            "aud",
            "purpose",
            "operation",
            "method",
            "actor",
            "realm",
            "target",
            "taskClient",
            "taskSubject",
            "payloadDigest",
            "jti",
            "nonce",
            "iat",
            "exp"),
        names);
    assertEquals("userservice", value.path("iss").asText());
    assertEquals("oriso-protected-invitations", value.path("aud").asText());
    assertEquals("INVITE_RESERVATIONS", value.path("purpose").asText());
    assertEquals("protected-invitation.cleanup-status", value.path("operation").asText());
    assertEquals("GET", value.path("method").asText());
    assertEquals("SETUP", value.path("actor").asText());
    assertEquals(account.path("realmName").asText(), value.path("realm").asText());
    assertEquals(
        "realm/"
            + account.path("realmId").asText()
            + "/protected-invitation/"
            + frozen().approval().approvalId(),
        value.path("target").asText());
    assertEquals(setup.clientId(), value.path("taskClient").asText());
    assertEquals(setup.serviceSubject(), value.path("taskSubject").asText());
    assertEquals(
        json.digest("protected-invitation-payload/v1", json.read("{}", JsonNode.class)),
        value.path("payloadDigest").asText());
    assertEquals(
        UUID.fromString(value.path("jti").asText()).toString(), value.path("jti").asText());
    assertTrue(value.path("nonce").asText().matches("[0-9a-f]{32}"));
    assertEquals(clock.instant().getEpochSecond(), value.path("iat").longValue());
    assertEquals(clock.instant().getEpochSecond() + 60, value.path("exp").longValue());
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      assertEquals(
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  mac.doFinal(
                      ("protected-invitation-proof/v1\n" + pieces[0])
                          .getBytes(StandardCharsets.UTF_8))),
          pieces[1]);
    } catch (java.security.GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }

  private FrozenCancellation frozen() {
    var av = account.path("approvalVector");
    var approval =
        approvals.freeze(
            UUID.fromString(av.path("approvalId").asText()),
            av.path("request").toString(),
            av.path("issuerRealm").asText(),
            av.path("issuerSubject").asText());
    return restrictions.freeze(
        approval,
        av.path("response").path("scopeDigest").asText(),
        UUID.fromString("ff088602-ad24-41f4-8cbf-5e6bed44f6fa"),
        Reason.REVOKED);
  }

  private ProtectedAccountCodec.VerifiedRecovery recovery() {
    var av = account.path("approvalVector");
    var frozen = frozen().approval();
    var claim = mock(AcceptanceClaim.class);
    when(claim.frozen()).thenReturn(frozen);
    when(claim.approval())
        .thenReturn(approvals.verifyAuthorized(frozen, av.path("response").toString()));
    when(claim.claimId()).thenReturn(UUID.fromString(account.path("claimId").asText()));
    when(claim.attemptId()).thenReturn(UUID.fromString(account.path("attemptId").asText()));
    when(claim.executionId()).thenReturn(UUID.fromString(account.path("executionId").asText()));
    when(claim.journalVersion()).thenReturn(1L);
    when(claim.registrationDigest()).thenReturn(account.path("registrationDigest").asText());
    var prepared =
        accounts.prepare(
            claim,
            account.path("registration"),
            json.read(account.path("account").toString(), AccountCreation.class));
    var metadata = accounts.restorePrepared(claim, accounts.checkpointPrepared(prepared));
    return accounts.verifyRecovery(
        accounts.prepareRecovery(
            metadata, 1, UUID.fromString(recoveryVector.path("recoveryId").asText())),
        bytes(vector.path("boundRecovery")));
  }

  private LocalOutcome local() {
    return json.read(account.path("localOutcome").toString(), LocalOutcome.class);
  }

  private KeycloakProtectedRestrictions transport(boolean installed) {
    return new KeycloakProtectedRestrictions(
        http,
        actors,
        broker,
        approvals,
        restrictions,
        "https://identity.example.test",
        account.path("realmName").asText(),
        installed ? cleanup : null);
  }

  private String url() {
    return "https://identity.example.test/realms/"
        + account.path("realmName").asText()
        + "/oriso-protected-invitations/v1/approvals/"
        + frozen().approval().approvalId()
        + "/cleanup-status";
  }

  private byte[] bytes(JsonNode value) {
    return json.canonical(value).getBytes(StandardCharsets.UTF_8);
  }

  private JsonNode resource(String name) {
    try (var in = getClass().getResourceAsStream("/scenario-c/" + name)) {
      return json.read(
          new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8),
          JsonNode.class);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }
}
