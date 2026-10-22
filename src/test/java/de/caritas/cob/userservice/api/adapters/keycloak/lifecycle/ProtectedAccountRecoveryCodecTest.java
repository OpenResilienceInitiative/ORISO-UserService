package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Independent canonical vectors exercise the wire codec, not SQL ownership or native effects. */
class ProtectedAccountRecoveryCodecTest {
  private final ObjectMapper nodes = new ObjectMapper();
  private final JsonNode original = resource("protected-account-v1.json");
  private final JsonNode vector = resource("protected-account-recovery-v1.json");
  private final byte[] key = HexFormat.of().parseHex(original.path("syntheticPublicKeyHex").asText());
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final MutableClock clock = new MutableClock();
  private final ProtectedInvitationCodec approvals = new ProtectedInvitationCodec(key, clock,
      original.path("realmName").asText(), original.path("realmId").asText());
  private final ProtectedAccountCodec codec = new ProtectedAccountCodec(key, clock, approvals,
      original.path("realmName").asText(), original.path("realmId").asText(), original.path("realmIssuer").asText());

  @Test
  void aDurablePreparedCheckpointRecoversAnUnknownCreateWithoutPasswordOrCreationReceipt() {
    var claim = claim();
    var prepared = codec.prepare(claim, original.path("registration"), account());
    String checkpoint = assertDoesNotThrow(() -> codec.checkpointPrepared(prepared));
    assertNotNull(checkpoint);
    String saved = new String(Base64.getUrlDecoder().decode(checkpoint.split("\\.")[0]), StandardCharsets.UTF_8);
    assertFalse(saved.contains(account().password()));
    assertFalse(saved.contains("\"password\""));
    assertFalse(saved.contains("\"account\""));
    assertFalse(saved.contains("\"body\""));
    clock.now = Instant.parse("2031-01-01T00:00:00Z");
    var metadata = assertDoesNotThrow(() -> codec.restorePrepared(claim, checkpoint));
    assertEquals(prepared.approvalId(), metadata.approvalId());
    assertEquals(prepared.attemptId(), metadata.attemptId());
    assertEquals(prepared.creationFingerprint(), metadata.creationFingerprint());
    var recovery = codec.prepareRecovery(metadata, 0, recoveryId());
    assertEquals(vector.path("recoverBody"), parse(codec.recoverBody(recovery)));
    assertFalse(metadata.toString().contains(prepared.creationFingerprint()));
    assertTrue(metadata.toString().contains("redacted"));
    assertThrows(IllegalArgumentException.class, () -> codec.createProof(prepared, creator()));
  }

  @Test
  void preparedMetadataCannotMoveToAnotherClaimOrBeRestoredByAnotherKeyOrReceiptKind() {
    var claim = claim();
    var prepared = codec.prepare(claim, original.path("registration"), account());
    String checkpoint = assertDoesNotThrow(() -> codec.checkpointPrepared(prepared));
    assertNotNull(codec.restorePrepared(claim, checkpoint));
    when(claim.claimId()).thenReturn(UUID.randomUUID());
    assertThrows(IllegalArgumentException.class, () -> codec.restorePrepared(claim, checkpoint));
    assertThrows(IllegalArgumentException.class, () -> codec.restorePrepared(claim(), checkpoint + "x"));
    var created = codec.verifyCreated(prepared, bytes(original.path("createdResponse")));
    assertThrows(IllegalArgumentException.class, () -> codec.restorePrepared(claim(), codec.checkpoint(created)));
    var foreign = new ProtectedAccountCodec(new byte[32], clock, approvals, original.path("realmName").asText(),
        original.path("realmId").asText(), original.path("realmIssuer").asText());
    assertThrows(IllegalArgumentException.class, () -> foreign.restorePrepared(claim(), checkpoint));
    var metadata = codec.restorePrepared(claim(), checkpoint);
    assertThrows(IllegalArgumentException.class, () -> codec.prepareRecovery(metadata, -1, recoveryId()));
    assertThrows(IllegalArgumentException.class, () -> codec.prepareRecovery(metadata, Long.MAX_VALUE, recoveryId()));
    assertThrows(IllegalArgumentException.class, () -> codec.prepareRecovery(metadata, 0, null));
  }

  @Test
  void authenticRecoveryBindsTheOriginalOwnerScopeAndNewExecutionAfterALostReply() {
    var pending = pending();
    var recovered = assertDoesNotThrow(() -> codec.verifyRecovery(pending, bytes(vector.path("recoveryResponse"))));
    assertNotNull(recovered);
    assertEquals("RECOVERY_CLAIMED", recovered.state());
    assertEquals(original.path("createdResponse").path("accountId").asText(), recovered.accountId());
    assertEquals(recoveryId(), recovered.executionId());
    assertEquals(1, recovered.generation());
    assertEquals(original.path("createdResponse").path("creationProof").asText(), recovered.creationProof());
    assertEquals(vector.path("compensateBody"), parse(codec.compensateBody(recovered, local())));
    assertTrue(recovered.toString().contains("redacted"));
  }

  @Test
  void absentRecoveryRequiresBothIndependentNativeReceiptsAndNoInventedAccount() throws Exception {
    var pending = pending();
    var absent = assertDoesNotThrow(() -> codec.verifyRecovery(pending, bytes(vector.path("abandonedResponse"))));
    assertNotNull(absent);
    assertEquals("ABANDONED", absent.state());
    assertNull(absent.accountId());
    assertNull(absent.creationProof());
    var body = parse(codec.compensateBody(absent, local()));
    assertTrue(body.path("accountId").isNull());
    assertTrue(body.path("creationProof").isNull());
    ObjectNode missing = vector.path("abandonedResponse").deepCopy();
    missing.putNull("terminalReceipt");
    assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, bytes(missing)));
    ObjectNode changed = vector.path("abandonedTerminalPayload").deepCopy();
    changed.put("accountId", "invented-account");
    missing.put("terminalReceipt", signed("terminal-receipt", changed));
    assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, bytes(missing)));
  }

  @Test
  void validMacCannotRebindAnyNativeRecoveryTupleOrUseAnOrdinaryScope() throws Exception {
    var pending = pending();
    assertNotNull(assertDoesNotThrow(() -> codec.verifyRecovery(pending, bytes(vector.path("recoveryResponse")))));
    var changes = List.<Consumer<ObjectNode>>of(
        p -> p.put("realmId", "foreign"), p -> p.put("attemptId", UUID.randomUUID().toString()),
        p -> p.put("accountId", "foreign"), p -> p.put("ownerClient", "backend-account-admin"),
        p -> p.put("ownerSubject", UUID.randomUUID().toString()), p -> p.put("creationFingerprint", "0".repeat(64)),
        p -> p.put("groupId", "foreign"), p -> p.put("roleFingerprint", "0".repeat(64)),
        p -> p.put("generation", 2), p -> p.put("executionId", UUID.randomUUID().toString()),
        p -> p.put("state", "COMMITTED"), p -> p.put("operation", "commit"),
        p -> p.put("commandDigest", "0".repeat(64)), p -> p.put("scope", "{}"),
        p -> p.set("scope", original.path("creationProofPayload").path("scope")), p -> p.put("extra", true));
    for (var change : changes) {
      ObjectNode payload = vector.path("recoveryPayload").deepCopy(); change.accept(payload);
      ObjectNode response = vector.path("recoveryResponse").deepCopy();
      response.put("recoveryReceipt", signed("recovery-receipt", payload));
      assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, bytes(response)));
    }
  }

  @Test
  void terminalCleanupHasItsOwnExactDigestAndCannotAcceptACommitOrAnotherGeneration() throws Exception {
    var pending = pending();
    var recovered = codec.verifyRecovery(pending, bytes(vector.path("recoveryResponse")));
    var cleanup = assertDoesNotThrow(() -> codec.verifyCompensated(recovered, local(), bytes(vector.path("compensatedResponse"))));
    assertNotNull(cleanup);
    assertEquals("COMPENSATED", cleanup.state());
    assertEquals(recovered.accountId(), cleanup.accountId());
    assertEquals(vector.path("compensatedResponse").path("terminalReceipt").asText(), cleanup.terminalReceipt());
    for (String field : List.of("realmId", "attemptId", "ownerClient", "ownerSubject", "accountId", "creationFingerprint", "groupId", "roleFingerprint", "scope", "state", "operation", "commandDigest", "executionId")) {
      ObjectNode payload = vector.path("compensatedTerminalPayload").deepCopy();
      payload.put(field, field.equals("operation") ? "commit" : "foreign");
      ObjectNode response = vector.path("compensatedResponse").deepCopy();
      response.put("terminalReceipt", signed("terminal-receipt", payload));
      assertThrows(IllegalArgumentException.class, () -> codec.verifyCompensated(recovered, local(), bytes(response)));
    }
    assertThrows(IllegalArgumentException.class, () -> codec.verifyCompensated(recovered, local(), bytes(original.path("committedResponse"))));
    assertTrue(cleanup.toString().contains("redacted"));
  }

  @Test
  void protectedRecoveryProofSelectsOnlyOriginalCreatorOrCanonicalAdminAndBindsOriginalBody() throws Exception {
    var pending = pending();
    for (var actor : List.of(creator(), admin())) {
      String proof = assertDoesNotThrow(() -> codec.recoverProof(pending, actor));
      checkProof(proof, actor, "protected-account.creation-recover", vector.path("recoverBody"));
      var recovered = codec.verifyRecovery(pending, bytes(vector.path("recoveryResponse")));
      checkProof(codec.compensateProof(recovered, local(), actor), actor, "protected-account.compensate", vector.path("compensateBody"));
    }
    for (var actor : List.of(
        new TaskActorCredentials(TaskActor.SETUP, "backend-setup", "synthetic", UUID.randomUUID().toString()),
        new TaskActorCredentials(TaskActor.ACCOUNT_CREATE, creator().clientId(), "synthetic", UUID.randomUUID().toString()),
        new TaskActorCredentials(TaskActor.ACCOUNT_ADMIN, "foreign-client", "synthetic", admin().serviceSubject()))) {
      assertThrows(IllegalArgumentException.class, () -> codec.recoverProof(pending, actor));
    }
  }

  @Test
  void responseBoundsStrictTypesAndMacDomainsRemainClosed() throws Exception {
    var pending = pending();
    assertNotNull(assertDoesNotThrow(() -> codec.verifyRecovery(pending, bytes(vector.path("recoveryResponse")))));
    ObjectNode response = vector.path("recoveryResponse").deepCopy();
    response.put("recoveryReceipt", signed("terminal-receipt", vector.path("recoveryPayload")));
    final ObjectNode wrongDomain = response;
    assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, bytes(wrongDomain)));
    response = vector.path("recoveryResponse").deepCopy();
    response.put("recoveryReceipt", "a".repeat(16001)); final ObjectNode oversized = response;
    assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, bytes(oversized)));
    assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, new byte[65537]));
    assertThrows(IllegalArgumentException.class, () -> codec.verifyRecovery(pending, "{} {}".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void exactCreatedProfileGettersExposeMetadataWithoutPassword() {
    var prepared = codec.prepare(claim(), original.path("registration"), account());
    var created = codec.verifyCreated(prepared, bytes(original.path("createdResponse")));
    assertEquals(account().email(), assertDoesNotThrow(created::email));
    assertEquals(account().firstName(), created.firstName());
    assertEquals(account().lastName(), created.lastName());
    assertEquals(account().preferredLanguage(), created.preferredLanguage());
  }

  private ProtectedAccountCodec.PreparedRecovery pending() {
    return assertDoesNotThrow(() -> {
      var c = claim(); var prepared = codec.prepare(c, original.path("registration"), account());
      var metadata = codec.restorePrepared(c, codec.checkpointPrepared(prepared));
      return codec.prepareRecovery(metadata, 0, recoveryId());
    });
  }
  private AcceptanceClaim claim() {
    var v = original.path("approvalVector");
    var frozen = approvals.freeze(UUID.fromString(v.path("approvalId").asText()), v.path("request").toString(), v.path("issuerRealm").asText(), v.path("issuerSubject").asText());
    var claim = mock(AcceptanceClaim.class);
    when(claim.frozen()).thenReturn(frozen);
    when(claim.approval()).thenReturn(approvals.verifyAuthorized(frozen, v.path("response").toString()));
    when(claim.claimId()).thenReturn(UUID.fromString(original.path("claimId").asText()));
    when(claim.attemptId()).thenReturn(UUID.fromString(original.path("attemptId").asText()));
    when(claim.executionId()).thenReturn(UUID.fromString(original.path("executionId").asText()));
    when(claim.journalVersion()).thenReturn(1L);
    when(claim.registrationDigest()).thenReturn(original.path("registrationDigest").asText()); return claim;
  }
  private AccountCreation account() { return json.read(original.path("account").toString(), AccountCreation.class); }
  private LocalOutcome local() { return json.read(original.path("localOutcome").toString(), LocalOutcome.class); }
  private UUID recoveryId() { return UUID.fromString(vector.path("recoveryId").asText()); }
  private TaskActorCredentials creator() { return new TaskActorCredentials(TaskActor.ACCOUNT_CREATE, "backend-account-create", "synthetic", original.path("approvalVector").path("request").path("scope").path("creatorSubject").asText()); }
  private TaskActorCredentials admin() { return new TaskActorCredentials(TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "synthetic", "091b8cef-09a8-4e5b-a244-6be3db4b7bee"); }
  private byte[] bytes(JsonNode value) { return json.canonical(value).getBytes(StandardCharsets.UTF_8); }
  private JsonNode parse(byte[] raw) { return json.read(json.utf8(raw), JsonNode.class); }
  private JsonNode resource(String name) { try (var in = getClass().getResourceAsStream("/scenario-c/" + name)) { return nodes.readTree(in); } catch (Exception failure) { throw new IllegalStateException("Synthetic fixture unavailable"); } }
  private String signed(String domain, JsonNode value) throws Exception {
    String e = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(value));
    return e + "." + HexFormat.of().formatHex(mac(domain + "\n" + e));
  }
  private byte[] mac(String value) throws Exception { var m = Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
  private void checkProof(String proof, TaskActorCredentials actor, String operation, JsonNode body) throws Exception {
    String[] parts = proof.split("\\."); assertEquals(2, parts.length);
    assertTrue(MessageDigest.isEqual(mac("protected-invitation-proof/v1\n" + parts[0]), Base64.getUrlDecoder().decode(parts[1])));
    var claims = nodes.readTree(Base64.getUrlDecoder().decode(parts[0]));
    assertEquals(15, claims.size()); assertEquals("userservice", claims.path("iss").asText());
    assertEquals("oriso-protected-invitations", claims.path("aud").asText());
    assertEquals(actor.actor().name(), claims.path("actor").asText());
    assertEquals(actor.actor() == TaskActor.ACCOUNT_CREATE ? "ACCOUNT_PROVISIONING" : "ACCOUNT_MAINTENANCE", claims.path("purpose").asText());
    assertEquals(operation, claims.path("operation").asText()); assertEquals("POST", claims.path("method").asText());
    assertEquals(original.path("realmName").asText(), claims.path("realm").asText());
    assertEquals("realm/" + original.path("realmId").asText() + "/protected-invitation/" + original.path("approvalVector").path("approvalId").asText() + "/account-attempt/" + original.path("attemptId").asText(), claims.path("target").asText());
    assertEquals(actor.clientId(), claims.path("taskClient").asText()); assertEquals(actor.serviceSubject(), claims.path("taskSubject").asText());
    assertEquals(json.digest("protected-invitation-payload/v1", body), claims.path("payloadDigest").asText());
    assertEquals(UUID.fromString(claims.path("jti").asText()).toString(), claims.path("jti").asText());
    assertTrue(claims.path("nonce").asText().matches("[a-f0-9]{32}"));
    assertEquals(clock.instant().getEpochSecond(), claims.path("iat").longValue());
    assertEquals(clock.instant().getEpochSecond()+60, claims.path("exp").longValue());
  }
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2030-01-01T00:00:00Z"); public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
  }
}
