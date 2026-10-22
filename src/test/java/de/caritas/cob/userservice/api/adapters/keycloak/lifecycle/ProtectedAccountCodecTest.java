package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Codec-input tests only. Mocked opaque claim getters confer no SQL/native ownership evidence. */
class ProtectedAccountCodecTest {
  private final ObjectMapper nodes = new ObjectMapper();
  private final JsonNode vector = vector();
  private final byte[] key = HexFormat.of().parseHex(vector.path("syntheticPublicKeyHex").asText());
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final MutableClock clock = new MutableClock();
  private final ProtectedInvitationCodec approvals =
      new ProtectedInvitationCodec(
          key, clock, vector.path("realmName").asText(), vector.path("realmId").asText());
  private final ProtectedAccountCodec codec =
      new ProtectedAccountCodec(
          key,
          clock,
          approvals,
          vector.path("realmName").asText(),
          vector.path("realmId").asText(),
          vector.path("realmIssuer").asText());

  @Test
  void preparesTheIndependentOriginalProtectedCreateBodyAndFingerprint() {
    var prepared = prepared();
    assertTrue(vector.path("realmName").asText().equals(codec.realmName()));
    assertTrue(
        vector
            .path("approvalVector")
            .path("approvalId")
            .asText()
            .equals(prepared.approvalId().toString()));
    assertTrue(vector.path("claimId").asText().equals(prepared.claimId().toString()));
    assertTrue(vector.path("attemptId").asText().equals(prepared.attemptId().toString()));
    assertTrue(vector.path("executionId").asText().equals(prepared.executionId().toString()));
    assertTrue(vector.path("creationFingerprint").asText().equals(prepared.creationFingerprint()));
    assertTrue(vector.path("createBody").equals(parse(codec.createBody(prepared))));
    byte[] changed = codec.createBody(prepared);
    changed[0] = 'x';
    assertTrue(vector.path("createBody").equals(parse(codec.createBody(prepared))));
  }

  @Test
  void registrationAndCanonicalRecipientCannotDriftFromThePrivateClaim() {
    var claim = claim();
    var changed = vector.path("registration").deepCopy();
    ((ObjectNode) changed).put("password", "another synthetic password");
    assertThrows(IllegalArgumentException.class, () -> codec.prepare(claim, changed, account()));
    var original = account();
    for (var foreign :
        List.of(
            new AccountCreation(
                original.username(),
                "foreign@example.test",
                original.firstName(),
                original.lastName(),
                original.preferredLanguage(),
                original.password(),
                false),
            new AccountCreation(
                "foreign",
                original.email(),
                original.firstName(),
                original.lastName(),
                original.preferredLanguage(),
                original.password(),
                false))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.prepare(claim, vector.path("registration"), foreign));
    }
  }

  @Test
  void opaqueGetterFixturesStillRequireAuthenticApprovalAndExactClaimShape() {
    var forged = claim();
    var good = forged.approval();
    var changedApproval = mock(ProtectedInvitationCodec.VerifiedApproval.class);
    when(changedApproval.approvalId()).thenReturn(good.approvalId());
    when(changedApproval.scopeDigest()).thenReturn("0".repeat(64));
    when(changedApproval.requestDigest()).thenReturn(good.requestDigest());
    when(changedApproval.receiptJson()).thenReturn(good.receiptJson());
    when(changedApproval.responseJson()).thenReturn(good.responseJson());
    when(forged.approval()).thenReturn(changedApproval);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.prepare(forged, vector.path("registration"), account()));
    var wrongVersion = claim();
    when(wrongVersion.journalVersion()).thenReturn(2L);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.prepare(wrongVersion, vector.path("registration"), account()));
    var wrongDigest = claim();
    when(wrongDigest.registrationDigest()).thenReturn("0".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.prepare(wrongDigest, vector.path("registration"), account()));
  }

  @Test
  void createProofHasAllFifteenExactProtectedClaimsAndIndependentMac() throws Exception {
    var prepared = prepared();
    checkActorProof(
        codec.createProof(prepared, creator()),
        "protected-account.create",
        "PUT",
        vector.path("createBody"),
        vector.path("createProofClaims").path("payloadDigest").asText());
  }

  @Test
  void anotherActorOrConfiguredOwnerCannotEmitTheProtectedAccountProof() {
    var prepared = prepared();
    for (var wrong :
        List.of(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                creator().clientId(),
                "synthetic",
                creator().serviceSubject()),
            new TaskActorCredentials(
                TaskActor.ACCOUNT_CREATE,
                "foreign-client",
                "synthetic",
                creator().serviceSubject()),
            new TaskActorCredentials(
                TaskActor.ACCOUNT_CREATE,
                creator().clientId(),
                "synthetic",
                "a3cb015f-e161-4e73-a334-eed0b7d91bf2"))) {
      assertThrows(IllegalArgumentException.class, () -> codec.createProof(prepared, wrong));
    }
  }

  @Test
  void nativeCreationProofProducesOnlyAnOpaqueOwnershipValue() {
    var created = created();
    var original = vector.path("approvalVector").path("request").path("scope");
    var authenticated = vector.path("creationProofPayload");
    assertTrue(vector.path("realmId").asText().equals(created.realmId()));
    assertTrue(
        vector
            .path("approvalVector")
            .path("approvalId")
            .asText()
            .equals(created.approvalId().toString()));
    assertTrue(original.path("workflowId").asText().equals(created.workflowId().toString()));
    assertTrue(original.path("inviteId").asText().equals(created.inviteId()));
    assertTrue(
        original.path("authorizationRevision").longValue() == created.authorizationRevision());
    assertTrue(original.path("targetTenantIds").get(0).longValue() == created.tenantId());
    assertTrue(original.path("protectedKind").asText().equals(created.registrationKind()));
    assertTrue(original.path("protectedProfileId").asText().equals(created.profileId()));
    assertTrue(authenticated.path("initialGroupId").asText().equals(created.initialGroupId()));
    assertTrue(
        authenticated
            .path("scope")
            .path("initialGroup")
            .asText()
            .equals(created.initialGroupPath()));
    assertTrue(
        authenticated
            .path("initialRoleFingerprint")
            .asText()
            .equals(created.initialRoleFingerprint()));
    assertTrue(vector.path("claimId").asText().equals(created.claimId().toString()));
    assertTrue(vector.path("attemptId").asText().equals(created.attemptId().toString()));
    assertTrue(vector.path("executionId").asText().equals(created.executionId().toString()));
    assertTrue(
        vector.path("createdResponse").path("accountId").asText().equals(created.accountId()));
    assertTrue(vector.path("creationFingerprint").asText().equals(created.creationFingerprint()));
    assertTrue(
        vector
            .path("createdResponse")
            .path("creationProof")
            .asText()
            .equals(created.creationProof()));
    assertTrue(vector.path("ownedReadBody").equals(parse(codec.ownedReadBody(created))));
  }

  @Test
  void independentlyResignedCreationOwnershipAndKnownScopeMutationsRefuse() throws Exception {
    var prepared = prepared();
    var mutations = new ArrayList<Consumer<ObjectNode>>();
    mutations.add(p -> p.put("v", 2));
    mutations.add(p -> p.put("realmName", "foreign"));
    mutations.add(p -> p.put("realmId", "foreign"));
    mutations.add(p -> p.put("attemptId", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"));
    mutations.add(p -> p.put("accountId", "foreign"));
    mutations.add(p -> p.put("ownerClient", "foreign"));
    mutations.add(p -> p.put("ownerSubject", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"));
    mutations.add(p -> p.putNull("initialGroupId"));
    mutations.add(p -> p.put("initialRoleFingerprint", "not-a-fingerprint"));
    mutations.add(p -> p.put("creationFingerprint", "0".repeat(64)));
    mutations.add(p -> ((ObjectNode) p.path("scope")).put("tenantId", "8"));
    mutations.add(p -> ((ObjectNode) p.path("scope")).put("registrationKind", "PLATFORM_ADMIN"));
    mutations.add(
        p ->
            ((ObjectNode) p.path("scope").path("origin"))
                .put("humanSubject", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"));
    mutations.add(
        p ->
            ((ObjectNode) p.path("scope").path("origin"))
                .put("humanIssuer", "https://foreign.example/realms/synthetic"));
    mutations.add(
        p ->
            ((ObjectNode) p.path("scope").path("approvalBinding"))
                .put("scopeDigest", "0".repeat(64)));
    mutations.add(
        p ->
            ((ObjectNode) p.path("scope").path("approvalBinding").path("acceptanceClaim"))
                .put("claimId", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"));
    mutations.add(
        p ->
            ((ObjectNode) p.path("scope").path("approvalBinding").path("acceptanceClaim"))
                .put("journalVersion", "1"));
    mutations.add(p -> p.put("extra", true));
    mutations.add(p -> p.remove("ownerClient"));
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (var mutation : mutations) {
      ObjectNode changed = vector.path("creationProofPayload").deepCopy();
      mutation.accept(changed);
      String proof = signed("creation-proof", changed, false);
      ObjectNode response = vector.path("createdResponse").deepCopy();
      response.put("creationProof", proof);
      checks.add(
          () ->
              assertThrows(
                  IllegalArgumentException.class,
                  () -> codec.verifyCreated(prepared, bytes(response))));
    }
    assertAll(checks);
  }

  @Test
  void creationProofRequiresCanonicalBase64JsonAndItsExistingDomain() throws Exception {
    var prepared = prepared();
    String proof = vector.path("createdResponse").path("creationProof").asText();
    String duplicate =
        "{\"v\":1," + json.canonical(vector.path("creationProofPayload")).substring(1);
    for (String invalid :
        List.of(
            proof + "=",
            proof + ".extra",
            signed("wrong-domain", vector.path("creationProofPayload"), false),
            signedRaw("creation-proof", duplicate, false),
            signedRaw(
                "creation-proof",
                json.canonical(vector.path("creationProofPayload")).replace("\"v\":1", "\"v\":1.0"),
                false),
            signed("creation-proof", vector.path("creationProofPayload"), true))) {
      ObjectNode response = vector.path("createdResponse").deepCopy();
      response.put("creationProof", invalid);
      assertThrows(
          IllegalArgumentException.class, () -> codec.verifyCreated(prepared, bytes(response)));
    }
  }

  @Test
  void createResponseCannotReplaceTheAttemptExecutionOrOpenOutcome() {
    var prepared = prepared();
    var changes =
        List.<Consumer<ObjectNode>>of(
            p -> p.put("attemptId", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"),
            p -> p.put("accountId", "foreign"),
            p -> p.put("state", "COMMITTED"),
            p -> ((ObjectNode) p.path("execution")).put("generation", 1),
            p -> ((ObjectNode) p.path("execution")).put("generation", "0"),
            p -> p.put("recoveryReceipt", "unexpected"),
            p -> p.put("terminalReceipt", "unexpected"),
            p -> p.put("extra", true),
            p -> p.remove("creationProof"));
    for (var change : changes) {
      ObjectNode response = vector.path("createdResponse").deepCopy();
      change.accept(response);
      assertThrows(
          IllegalArgumentException.class, () -> codec.verifyCreated(prepared, bytes(response)));
    }
  }

  @Test
  void expiryStopsFreshCreationWithoutDiscardingAnExistingPreparedOwnershipReply() {
    var saved = claim();
    var prepared = codec.prepare(saved, vector.path("registration"), account());
    assertNotNull(prepared);
    clock.now = Instant.parse("2030-01-03T00:00:00Z");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.prepare(saved, vector.path("registration"), account()));
    assertThrows(IllegalArgumentException.class, () -> codec.createProof(prepared, creator()));
    assertNotNull(
        assertDoesNotThrow(
            () -> codec.verifyCreated(prepared, bytes(vector.path("createdResponse")))));
  }

  @Test
  void ownedProjectionChecksArePureAndRequireTheExactDisabledPendingProfile() throws Exception {
    var created = created();
    assertDoesNotThrow(
        () -> codec.checkOwnedProjection(created, bytes(vector.path("ownedProjection"))));
    checkActorProof(
        codec.ownedReadProof(created, creator()),
        "protected-account.owned-read",
        "POST",
        vector.path("ownedReadBody"),
        json.digest("protected-invitation-payload/v1", vector.path("ownedReadBody")));
    var changes =
        List.<Consumer<ObjectNode>>of(
            p -> p.put("id", "foreign"),
            p -> p.put("username", "foreign"),
            p -> p.put("email", "foreign@example.test"),
            p -> p.put("firstName", "Foreign"),
            p -> p.put("lastName", ""),
            p -> p.put("tenantId", "8"),
            p -> p.put("preferredLanguage", "en"),
            p -> p.put("enabled", true),
            p -> p.put("emailVerified", false),
            p -> p.put("passwordChangeRequired", true),
            p -> p.putArray("effectiveRoles").add("tenant-admin"),
            p -> p.put("enabled", "false"),
            p -> p.put("extra", true),
            p -> p.remove("tenantId"));
    for (var change : changes) {
      ObjectNode projection = vector.path("ownedProjection").deepCopy();
      change.accept(projection);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.checkOwnedProjection(created, bytes(projection)));
    }
  }

  @Test
  void commitBindsTheOriginalProtectedCommandAndExactLocalOutcome() throws Exception {
    var created = created();
    assertTrue(vector.path("commitBody").equals(parse(codec.commitBody(created, local()))));
    checkActorProof(
        codec.commitProof(created, local(), creator()),
        "protected-account.commit",
        "POST",
        vector.path("commitBody"),
        vector.path("terminalPayload").path("commandDigest").asText());
  }

  @Test
  void terminalReceiptAuthenticatesTheExactCommittedOwnershipAndLocalBody() {
    var created = created();
    var committed =
        codec.verifyCommitted(created, local(), bytes(vector.path("committedResponse")));
    assertNotNull(committed);
    assertTrue(vector.path("claimId").asText().equals(committed.claimId().toString()));
    assertTrue(created.attemptId().equals(committed.attemptId()));
    assertTrue(created.accountId().equals(committed.accountId()));
    assertTrue(
        vector
            .path("committedResponse")
            .path("terminalReceipt")
            .asText()
            .equals(committed.terminalReceipt()));
  }

  @Test
  void independentlyResignedTerminalFieldsAndForeignLocalOutcomesRefuse() throws Exception {
    var created = created();
    var changes =
        List.<Consumer<ObjectNode>>of(
            p -> p.put("realmId", "foreign"),
            p -> p.put("attemptId", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"),
            p -> p.put("accountId", "foreign"),
            p -> p.put("ownerClient", "foreign"),
            p -> p.put("ownerSubject", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"),
            p -> p.put("scope", "{}"),
            p -> p.put("creationFingerprint", "0".repeat(64)),
            p -> p.put("groupId", "foreign"),
            p -> p.put("roleFingerprint", "0".repeat(64)),
            p -> p.put("state", "OPEN"),
            p -> p.put("generation", 1),
            p -> p.put("executionId", "a3cb015f-e161-4e73-a334-eed0b7d91bf2"),
            p -> p.put("operation", "compensate"),
            p -> p.put("commandDigest", "0".repeat(64)),
            p -> p.put("generation", "0"),
            p -> p.put("extra", true),
            p -> p.remove("ownerClient"));
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (var change : changes) {
      ObjectNode payload = vector.path("terminalPayload").deepCopy();
      change.accept(payload);
      ObjectNode response = vector.path("committedResponse").deepCopy();
      response.put("terminalReceipt", signed("terminal-receipt", payload, true));
      checks.add(
          () ->
              assertThrows(
                  IllegalArgumentException.class,
                  () -> codec.verifyCommitted(created, local(), bytes(response))));
    }
    assertAll(checks);
    var foreign =
        new LocalOutcome(
            local().journalVersion(),
            UUID.fromString("a3cb015f-e161-4e73-a334-eed0b7d91bf2"),
            local().effectsDigest());
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyCommitted(created, foreign, bytes(vector.path("committedResponse"))));
    ObjectNode translated = nodes.createObjectNode();
    translated
        .putObject("identity")
        .set("scope", vector.path("creationProofPayload").path("scope"));
    translated.set("execution", vector.path("commitBody").path("execution"));
    translated.set("localCommit", vector.path("commitBody").path("localCommit"));
    ObjectNode wrongDigest = vector.path("terminalPayload").deepCopy();
    wrongDigest.put("commandDigest", json.digest("protected-invitation-payload/v1", translated));
    ObjectNode response = vector.path("committedResponse").deepCopy();
    response.put("terminalReceipt", signed("terminal-receipt", wrongDigest, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyCommitted(created, local(), bytes(response)));
  }

  @Test
  void handlesAndFailuresNeverDescribePasswordBodiesOrReceipts() {
    var prepared = prepared();
    var created = codec.verifyCreated(prepared, bytes(vector.path("createdResponse")));
    assertNotNull(created);
    var committed =
        codec.verifyCommitted(created, local(), bytes(vector.path("committedResponse")));
    assertNotNull(committed);
    for (Object safe : List.of(prepared, created, committed)) {
      assertTrue(safe.toString().contains("redacted"));
      assertFalse(safe.toString().contains(account().password()));
      assertFalse(safe.toString().contains(created.creationProof()));
      assertFalse(safe.toString().contains(created.accountId()));
    }
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> codec.verifyCreated(prepared, new byte[] {'{'}));
    assertFalse(String.valueOf(failure.getMessage()).contains(account().password()));
    assertFalse(String.valueOf(failure.getMessage()).contains(created.creationProof()));
  }

  @Test
  void aPersistedOwnedCheckpointResumesWithoutPasswordAndCannotMoveToAnotherClaim() {
    var originalClaim = claim();
    var original =
        codec.verifyCreated(
            codec.prepare(originalClaim, vector.path("registration"), account()),
            bytes(vector.path("createdResponse")));
    String saved = codec.checkpoint(original);
    String payload =
        new String(Base64.getUrlDecoder().decode(saved.split("\\.")[0]), StandardCharsets.UTF_8);
    assertFalse(payload.contains(account().password()));
    assertFalse(payload.contains("\"password\":"));
    var restored = codec.restore(originalClaim, saved);
    assertEquals(original.accountId(), restored.accountId());
    assertEquals(original.creationProof(), restored.creationProof());
    assertEquals(parse(codec.ownedReadBody(original)), parse(codec.ownedReadBody(restored)));
    assertEquals(
        parse(codec.commitBody(original, local())), parse(codec.commitBody(restored, local())));
    clock.now = Instant.parse("2030-01-03T00:00:00Z");
    assertNotNull(codec.restore(originalClaim, saved));
    when(originalClaim.claimId()).thenReturn(UUID.randomUUID());
    assertThrows(IllegalArgumentException.class, () -> codec.restore(originalClaim, saved));
    assertThrows(IllegalArgumentException.class, () -> codec.restore(originalClaim, saved + "x"));
  }

  private ProtectedAccountCodec.PreparedCreation prepared() {
    var value = codec.prepare(claim(), vector.path("registration"), account());
    assertNotNull(value, "Prepared protected creation is unavailable");
    return value;
  }

  private ProtectedAccountCodec.VerifiedCreated created() {
    var value = codec.verifyCreated(prepared(), bytes(vector.path("createdResponse")));
    assertNotNull(value, "Verified native creation ownership is unavailable");
    return value;
  }

  private AcceptanceClaim claim() {
    var v = vector.path("approvalVector");
    var frozen =
        approvals.freeze(
            UUID.fromString(v.path("approvalId").asText()),
            v.path("request").toString(),
            v.path("issuerRealm").asText(),
            v.path("issuerSubject").asText());
    var approved = approvals.verifyAuthorized(frozen, v.path("response").toString());
    var claim = mock(AcceptanceClaim.class);
    when(claim.frozen()).thenReturn(frozen);
    when(claim.approval()).thenReturn(approved);
    when(claim.claimId()).thenReturn(UUID.fromString(vector.path("claimId").asText()));
    when(claim.attemptId()).thenReturn(UUID.fromString(vector.path("attemptId").asText()));
    when(claim.executionId()).thenReturn(UUID.fromString(vector.path("executionId").asText()));
    when(claim.journalVersion()).thenReturn(1L);
    when(claim.registrationDigest()).thenReturn(vector.path("registrationDigest").asText());
    return claim;
  }

  private AccountCreation account() {
    return json.read(vector.path("account").toString(), AccountCreation.class);
  }

  private LocalOutcome local() {
    return json.read(vector.path("localOutcome").toString(), LocalOutcome.class);
  }

  private TaskActorCredentials creator() {
    return new TaskActorCredentials(
        TaskActor.ACCOUNT_CREATE,
        "backend-account-create",
        "synthetic-not-used-for-proof",
        vector
            .path("approvalVector")
            .path("request")
            .path("scope")
            .path("creatorSubject")
            .asText());
  }

  private byte[] bytes(JsonNode value) {
    return json.canonical(value).getBytes(StandardCharsets.UTF_8);
  }

  private JsonNode parse(byte[] raw) {
    return json.read(json.utf8(raw), JsonNode.class);
  }

  private void checkActorProof(
      String proof, String operation, String method, JsonNode body, String digest)
      throws Exception {
    assertNotNull(proof);
    String[] parts = proof.split("\\.", -1);
    assertEquals(2, parts.length);
    byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
    assertTrue(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(parts[0]));
    assertTrue(
        MessageDigest.isEqual(
            mac("protected-invitation-proof/v1\n" + parts[0]),
            Base64.getUrlDecoder().decode(parts[1])));
    JsonNode actual = json.read(json.utf8(decoded), JsonNode.class);
    ObjectNode expected = vector.path("createProofClaims").deepCopy();
    expected.put("operation", operation);
    expected.put("method", method);
    expected.put("payloadDigest", digest);
    expected.set("jti", actual.path("jti"));
    expected.set("nonce", actual.path("nonce"));
    assertTrue(expected.equals(actual));
    assertTrue(
        UUID.fromString(actual.path("jti").asText())
            .toString()
            .equals(actual.path("jti").asText()));
    assertTrue(actual.path("nonce").asText().matches("[0-9a-f]{32}"));
    assertTrue(digest.equals(json.digest("protected-invitation-payload/v1", body)));
  }

  private String signed(String domain, JsonNode value, boolean hex) throws Exception {
    return signedRaw(domain, json.canonical(value), hex);
  }

  private String signedRaw(String domain, String payload, boolean hex) throws Exception {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    byte[] signature = mac(domain + "\n" + encoded);
    return encoded
        + "."
        + (hex
            ? HexFormat.of().formatHex(signature)
            : Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
  }

  private byte[] mac(String input) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
  }

  private static JsonNode vector() {
    try (var input =
        ProtectedAccountCodecTest.class.getResourceAsStream(
            "/scenario-c/protected-account-v1.json")) {
      if (input == null) throw new IllegalStateException("Public synthetic vector unavailable");
      return new ObjectMapper().readTree(input);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Public synthetic vector unavailable");
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2030-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
