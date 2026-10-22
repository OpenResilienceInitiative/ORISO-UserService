package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Public synthetic vectors use the receiver's wire domains and an independent Python HMAC. */
class ProtectedInvitationCodecTest {
  private final ObjectMapper json = new ObjectMapper();
  private final JsonNode vector = vector();
  private final byte[] key = HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText());
  private final AccountLifecycleCodec ordinary = new AccountLifecycleCodec(key);
  private final ProtectedInvitationCodec codec =
      new ProtectedInvitationCodec(
          key,
          Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
          "synthetic",
          vector.path("issuerRealm").asText());

  @Test
  void freezesTheExactNativeRequestDigestAndCanonicalProposal() {
    var frozen = frozen();
    assertEquals(vector.path("requestDigest").asText(), frozen.requestDigest());
    assertEquals(ordinary.canonical(vector.path("request")), frozen.requestJson());
    assertEquals(vector.path("issuerRealm").asText(), frozen.issuerRealm());
    assertEquals(vector.path("issuerSubject").asText(), frozen.issuerSubject());
  }

  @Test
  void authenticatesTheIndependentReceiverReceiptBeforeMakingAnInvitationReady() {
    var ready = codec.verifyAuthorized(frozen(), response().toString());
    assertEquals(UUID.fromString(vector.path("approvalId").asText()), ready.approvalId());
    assertEquals("a".repeat(64), ready.scopeDigest());
    assertEquals(vector.path("requestDigest").asText(), ready.requestDigest());
    assertEquals(ordinary.canonical(response().path("approvalReceipt")), ready.receiptJson());
  }

  @Test
  void rejectsUnknownMissingDuplicateCoercedAndTrailingResponseFields() {
    var frozen = frozen();
    var unknown = response();
    unknown.put("allowed", true);
    var missing = response();
    missing.remove("approvalRequestDigest");
    var coerced = response();
    coerced.put("version", "0");
    var duplicate = response().toString().replaceFirst("\\{", "{\"state\":\"AUTHORIZED\",");
    for (String candidate :
        new String[] {
          unknown.toString(), missing.toString(), coerced.toString(), duplicate, response() + " {}"
        }) {
      assertThrows(IllegalArgumentException.class, () -> codec.verifyAuthorized(frozen, candidate));
    }
  }

  @Test
  void rejectsAnOtherwiseValidReceiptForAnotherApproval() {
    var changed = response();
    changed.put("approvalId", UUID.randomUUID().toString());
    assertThrows(
        IllegalArgumentException.class, () -> codec.verifyAuthorized(frozen(), changed.toString()));
  }

  @Test
  void rejectsAReceiptWhoseAuthenticatedPayloadDiffersFromItsReturnedFields() {
    var changed = response();
    ((ObjectNode) changed.path("approvalReceipt")).put("profileVersion", 99);
    assertThrows(
        IllegalArgumentException.class, () -> codec.verifyAuthorized(frozen(), changed.toString()));
  }

  @Test
  void rejectsAnInvalidNativeReceiptMacWithoutExposingTheReceipt() {
    var changed = response();
    var receipt = (ObjectNode) changed.path("approvalReceipt");
    receipt.put("receipt", receipt.path("receipt").asText().split("\\.")[0] + "." + "0".repeat(64));
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.verifyAuthorized(frozen(), changed.toString()));
    assertFalse(error.getMessage().contains(vector.path("issuerSubject").asText()));
    assertFalse(error.getMessage().contains(receipt.path("receipt").asText()));
  }

  @Test
  void aRestrictionOnlyCancellationTombstoneCanNeverBecomeAnApproval() {
    var tombstone = response();
    tombstone.put("state", "CANCELLED");
    tombstone.putNull("approvalReceipt");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyAuthorized(frozen(), tombstone.toString()));
  }

  @Test
  void boundConsumedOrFencedAuthorityCannotReleaseAnotherUsableInvitation() {
    var frozen = frozen();
    for (String state : Set.of("BOUND", "CONSUMED", "EXPIRED", "CANCELLED")) {
      var changed = response();
      changed.put("state", state);
      assertThrows(
          IllegalArgumentException.class, () -> codec.verifyAuthorized(frozen, changed.toString()));
    }
    var bound = response();
    bound.put("boundAttemptId", UUID.randomUUID().toString());
    assertThrows(
        IllegalArgumentException.class, () -> codec.verifyAuthorized(frozen, bound.toString()));
  }

  @Test
  void expiredNativeAuthorityDoesNotBecomeMailReady() {
    var expired =
        new ProtectedInvitationCodec(
            key,
            Clock.fixed(Instant.parse("2030-01-02T03:04:05Z"), ZoneOffset.UTC),
            "synthetic",
            vector.path("issuerRealm").asText());
    assertThrows(
        IllegalArgumentException.class,
        () -> expired.verifyAuthorized(frozen(), response().toString()));
  }

  @Test
  void foreignFrozenOriginCannotBeReconciledUsingTheOriginalReceipt() {
    var changed =
        codec.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            vector.path("request").toString(),
            vector.path("issuerRealm").asText(),
            "d77343f3-02c7-487f-9f7b-0f457dce97fb");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyAuthorized(changed, response().toString()));
  }

  @Test
  void rejectsMalformedOrUnresolvedProposalsBeforeAnyHumanDispatch() {
    var unknown = vector.path("request").deepCopy();
    ((ObjectNode) unknown.path("scope")).put("permission", "manage-users");
    var unresolved = vector.path("request").deepCopy();
    ((ObjectNode) unresolved.path("scope")).putNull("targetTenantIds");
    var mixed = vector.path("request").deepCopy();
    ((ObjectNode) mixed.path("scope")).put("recipient", " Ada@Example.test ");
    for (String candidate :
        new String[] {unknown.toString(), unresolved.toString(), mixed.toString()}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              codec.freeze(
                  UUID.fromString(vector.path("approvalId").asText()),
                  candidate,
                  vector.path("issuerRealm").asText(),
                  vector.path("issuerSubject").asText()));
    }
  }

  @Test
  void statusRecoveryUsesOnlyTheSavedProposalAndExactSetupProofDomain() throws Exception {
    var frozen = frozen();
    var actor =
        new TaskActorCredentials(
            TaskActor.SETUP,
            "backend-setup",
            "public-synthetic-secret",
            "4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed");
    JsonNode body = json.readTree(codec.statusBody(frozen));
    assertEquals(
        vector.path("requestDigest").asText(), body.path("approvalRequestDigest").asText());
    assertEquals(vector.path("request").path("scope").path("workflowId"), body.path("workflowId"));
    assertTrue(body.path("scopeDigest").isNull());
    assertTrue(body.path("attemptId").isNull());
    String[] proof = codec.statusProof(frozen, actor).split("\\.");
    JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(proof[0]));
    assertEquals(15, claims.size());
    assertEquals("oriso-protected-invitations", claims.path("aud").asText());
    assertEquals("INVITE_RESERVATIONS", claims.path("purpose").asText());
    assertEquals("protected-invitation.status", claims.path("operation").asText());
    assertEquals("SETUP", claims.path("actor").asText());
    assertEquals("POST", claims.path("method").asText());
    assertEquals(actor.clientId(), claims.path("taskClient").asText());
    assertEquals(actor.serviceSubject(), claims.path("taskSubject").asText());
    assertEquals(
        "realm/"
            + vector.path("issuerRealm").asText()
            + "/protected-invitation/"
            + frozen.approvalId(),
        claims.path("target").asText());
    assertEquals(
        hexMac("protected-invitation-payload/v1\n" + ordinary.canonical(body)),
        claims.path("payloadDigest").asText());
    assertEquals(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac("protected-invitation-proof/v1\n" + proof[0])),
        proof[1]);
    assertEquals(60, claims.path("exp").asLong() - claims.path("iat").asLong());
  }

  @Test
  void aDifferentMachineActorCannotCreateTheSetupRecoveryProof() {
    var frozen = frozen();
    var wrong =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_CREATE,
            "backend-account-create",
            "public-synthetic-secret",
            "259d23a0-5b65-4559-8cc1-aa5683961d02");
    assertThrows(IllegalArgumentException.class, () -> codec.statusProof(frozen, wrong));
  }

  @Test
  void savedScopeCannotBeReplacedWhileKeepingItsOriginalDigest() {
    var original = frozen();
    var request = vector.path("request").deepCopy();
    ((ObjectNode) request.path("scope")).put("recipient", "other@example.test");
    var forged =
        new ProtectedInvitationCodec.FrozenApproval(
            original.approvalId(),
            ordinary.canonical(request),
            original.issuerRealm(),
            original.issuerSubject(),
            original.requestDigest());
    assertThrows(
        IllegalArgumentException.class, () -> codec.verifyAuthorized(forged, response().toString()));
    assertThrows(IllegalArgumentException.class, () -> codec.statusBody(forged));
  }

  private ProtectedInvitationCodec.FrozenApproval frozen() {
    var frozen =
        codec.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            vector.path("request").toString(),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    assertNotNull(frozen, "The exact approval proposal must be durably freezeable");
    return frozen;
  }

  private ObjectNode response() {
    return (ObjectNode) vector.path("response").deepCopy();
  }

  private JsonNode vector() {
    try (var input = getClass().getResourceAsStream("/scenario-c/protected-approval-v1.json")) {
      assertNotNull(input);
      return json.readTree(input);
    } catch (java.io.IOException failure) {
      throw new AssertionError("Synthetic wire vector is unreadable", failure);
    }
  }

  private String hexMac(String value) throws Exception {
    return HexFormat.of().formatHex(mac(value));
  }

  private byte[] mac(String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }
}
