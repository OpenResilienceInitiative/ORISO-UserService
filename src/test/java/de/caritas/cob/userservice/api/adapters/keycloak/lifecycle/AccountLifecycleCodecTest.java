package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountLifecycleCodecTest {
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);

  @Test
  void canonicalObjectsSortRecursivelyAndKeepExplicitNulls() {
    assertEquals(
        "{\"a\":{\"a\":true,\"z\":2},\"z\":[\"2\",\"11\"]}",
        codec.canonical(Map.of("z", List.of("2", "11"), "a", Map.of("z", 2, "a", true))));
    assertEquals(
        "{\"email\":null,\"firstName\":null,\"lastName\":null,\"password\":null,\"passwordTemporary\":false,\"preferredLanguage\":null,\"username\":\"synthetic\"}",
        codec.canonical(new AccountCreation("synthetic", null, null, null, null, null, false)));
  }

  @Test
  void duplicateUnknownMissingAndCoercedFieldsAreRejected() {
    String valid =
        codec.canonical(new AccountCreation("synthetic", null, null, null, null, null, false));
    assertNotNull(codec.read(valid, AccountCreation.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.read(valid.replace("\"email\":null,", ""), AccountCreation.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.read(
                valid.replace("\"email\":null", "\"email\":null,\"email\":null"),
                AccountCreation.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.read(valid.replace("{", "{\"enabled\":true,"), AccountCreation.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.read(valid.replace("false", "\"false\""), AccountCreation.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.read(valid.replace("false", "null"), AccountCreation.class));
  }

  @Test
  void proofBearingRecordsAndPendingOutboxDiagnosticsAreRedacted() {
    var identity =
        new CreationIdentity(
            new CreationScope(
                "7",
                "ASKER",
                "/ordinary/asker",
                new Origin("REGISTRATION", "workflow", "provenance", null, null, null, List.of())),
            "a".repeat(64));
    var execution = new ExecutionRef(java.util.UUID.randomUUID(), 0);
    var outcome = new LocalOutcome(1, java.util.UUID.randomUUID(), "b".repeat(64));
    String proof = "synthetic-opaque-proof-must-not-appear";
    var attempt =
        new AccountLifecycleJournal.Attempt(
            java.util.UUID.randomUUID(),
            identity,
            execution,
            execution.executionId(),
            "account",
            proof,
            "OPEN",
            1);
    List<Object> values =
        List.of(
            new OwnedReadRequest(identity, execution, "account", proof),
            new CommitRequest(identity, execution, "account", proof, outcome),
            new CompensateRequest(identity, execution, "account", proof, outcome),
            attempt,
            new AccountLifecycleJournal.Pending(
                attempt, java.util.UUID.randomUUID(), "COMMIT", proof));
    for (Object value : values) assertFalse(value.toString().contains(proof));
  }

  @Test
  void sharedPythonVectorVerifiesExactCreationOwnershipAndNativeGroupSnapshot() throws Exception {
    var vector = wireVector();
    var verifier =
        new AccountLifecycleCodec(
            java.util.HexFormat.of().parseHex(vector.path("keyHex").asText()));
    var payload =
        verifier.read(
            vector.path("canonicalPayload").asText(),
            AccountLifecycleCodec.CreationProofPayload.class);
    var identity = new CreationIdentity(payload.scope(), payload.creationFingerprint());
    var verified =
        verifier.verifyCreationProof(
            vector.path("proof").asText(),
            payload.realmName(),
            payload.realmId(),
            payload.ownerClient(),
            payload.ownerSubject(),
            payload.attemptId(),
            payload.accountId(),
            identity);
    assertEquals(payload, verified);
    assertEquals(
        verifier.digest("initial-roles", List.of("user")), verified.initialRoleFingerprint());
    assertFalse(verified.toString().contains(payload.ownerSubject()));
  }

  @Test
  void tamperedAndForeignCreationReceiptsCannotCrossCallerBoundary() throws Exception {
    var vector = wireVector();
    var verifier =
        new AccountLifecycleCodec(
            java.util.HexFormat.of().parseHex(vector.path("keyHex").asText()));
    var payload =
        verifier.read(
            vector.path("canonicalPayload").asText(),
            AccountLifecycleCodec.CreationProofPayload.class);
    String proof = vector.path("proof").asText();
    var identity = new CreationIdentity(payload.scope(), payload.creationFingerprint());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifier.verifyCreationProof(
                proof + "x",
                payload.realmName(),
                payload.realmId(),
                payload.ownerClient(),
                payload.ownerSubject(),
                payload.attemptId(),
                payload.accountId(),
                identity));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifier.verifyCreationProof(
                proof,
                payload.realmName(),
                payload.realmId(),
                "foreign-creator",
                payload.ownerSubject(),
                payload.attemptId(),
                payload.accountId(),
                identity));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifier.verifyCreationProof(
                proof,
                payload.realmName(),
                payload.realmId(),
                payload.ownerClient(),
                payload.ownerSubject(),
                payload.attemptId(),
                "foreign-account",
                identity));
    var foreign =
        new CreationIdentity(
            new CreationScope(
                "8", "ASKER", payload.scope().initialGroup(), payload.scope().origin()),
            identity.creationFingerprint());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            verifier.verifyCreationProof(
                proof,
                payload.realmName(),
                payload.realmId(),
                payload.ownerClient(),
                payload.ownerSubject(),
                payload.attemptId(),
                payload.accountId(),
                foreign));
  }

  @Test
  void validMacCannotHideDuplicateUnknownMissingOrCoercedReceiptFields() throws Exception {
    var vector = wireVector();
    byte[] key = java.util.HexFormat.of().parseHex(vector.path("keyHex").asText());
    var verifier = new AccountLifecycleCodec(key);
    String json = vector.path("canonicalPayload").asText();
    var payload = verifier.read(json, AccountLifecycleCodec.CreationProofPayload.class);
    var identity = new CreationIdentity(payload.scope(), payload.creationFingerprint());
    for (String malformed :
        List.of(
            json.replace("\"v\":1", "\"v\":1,\"v\":1"),
            json.replace("{", "{\"unknown\":null,"),
            json.replace("\"v\":1", "\"v\":\"1\""),
            json.replace("\"v\":1", "\"v\":2"),
            json.replace(",\"v\":1", ""))) {
      String proof = signedFixtureProof(key, malformed);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              verifier.verifyCreationProof(
                  proof,
                  payload.realmName(),
                  payload.realmId(),
                  payload.ownerClient(),
                  payload.ownerSubject(),
                  payload.attemptId(),
                  payload.accountId(),
                  identity));
    }
    assertThrows(
        IllegalArgumentException.class, () -> verifier.utf8(new byte[] {(byte) 0xc3, (byte) 0x28}));
  }

  private com.fasterxml.jackson.databind.JsonNode wireVector() throws Exception {
    try (var stream =
        getClass().getResourceAsStream("/identity-lifecycle/creation-proof-v1.json")) {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(stream);
    }
  }

  private String signedFixtureProof(byte[] key, String json) throws Exception {
    String payload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
    return payload
        + "."
        + java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal(
                    ("creation-proof\n" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  void executionUuidIsCanonicalAndVerifiedImportCanRetainCompleteOriginalHumanProvenance() {
    String execution =
        "{\"executionId\":\"abcdefab-1234-4234-8234-123456abcdef\",\"generation\":0}";
    assertNotNull(codec.read(execution, ExecutionRef.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.read(
                execution
                    .toUpperCase(java.util.Locale.ROOT)
                    .replace("EXECUTIONID", "executionId")
                    .replace("GENERATION", "generation"),
                ExecutionRef.class));
    assertDoesNotThrow(
        () ->
            new Origin(
                "IMPORT",
                "workflow",
                "import:one",
                "https://issuer.example.test",
                "human-subject",
                "CONSULTANT_IMPORT",
                List.of("12")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Origin(
                "IMPORT", "workflow", "import:one", null, "human-subject", null, List.of("12")));
  }

  @Test
  void protectedKindsCannotEnterOrdinaryScope() {
    var origin = new Origin("REGISTRATION", "workflow", "provenance", null, null, null, List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreationScope("7", "TENANT_ADMIN", "/protected/admin", origin));
  }

  @Test
  void floatingPayloadAndShortKeyAreDenied() {
    assertThrows(IllegalArgumentException.class, () -> codec.canonical(Map.of("float", 1.5)));
    assertThrows(IllegalArgumentException.class, () -> new AccountLifecycleCodec(new byte[31]));
  }
}
