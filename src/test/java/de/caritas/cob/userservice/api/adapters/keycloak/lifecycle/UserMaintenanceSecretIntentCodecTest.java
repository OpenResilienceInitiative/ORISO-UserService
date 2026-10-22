package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent fixed bytes exercise data protection only, never native or caller authority. */
class UserMaintenanceSecretIntentCodecTest {
  @Test
  void integerTenantMatchesIndependentCanonicalAndCryptoVector() throws Exception {
    independentVector("ordinary_tenant_7");
  }

  @Test
  void explicitNullTenantMatchesIndependentCanonicalAndCryptoVector() throws Exception {
    independentVector("explicitly_permitted_null_tenant");
  }

  @Test
  void authenticatedCipherRejectsChangedCiphertextNonceAndRootKeyWithoutDetails() throws Exception {
    var vector = ordinaryVector();
    var metadata = vector.path("metadata");
    var codec = vectorCodec();
    String original = vector.path("canonical_envelope_utf8").textValue();
    for (String field : List.of("nonce", "ciphertext")) {
      var envelope = codec.read(original, ObjectNode.class);
      byte[] bytes = Base64.getUrlDecoder().decode(envelope.path(field).textValue());
      bytes[0] ^= 1;
      envelope.put(field, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
      refuses(() -> codec.openSelfAppIntent(metadata, codec.canonical(envelope)));
    }
    var wrongKey = new AccountLifecycleCodec(new byte[32]);
    refuses(() -> wrongKey.openSelfAppIntent(metadata, original));
  }

  @Test
  void decryptRequiresTheSeparatelyCheckedOriginalMetadataRatherThanEnvelopeClaims()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    String envelope = vector.path("canonical_envelope_utf8").textValue();
    for (String field : List.of("requestDigest", "expectedState", "originDigest")) {
      var changed = vector.path("metadata").deepCopy();
      ((ObjectNode) changed).put(field, "b".repeat(64));
      refuses(() -> codec.openSelfAppIntent(changed, envelope));
    }
    var changed = (ObjectNode) vector.path("metadata").deepCopy();
    changed.put("authorityValidUntil", changed.path("authorityValidUntil").longValue() + 1);
    refuses(() -> codec.openSelfAppIntent(changed, envelope));
    changed.put("tenantId", 8);
    refuses(() -> codec.openSelfAppIntent(changed, envelope));
  }

  @Test
  void everyMetadataKeyIsMandatoryAndUnknownOrCoercedFieldsCannotSeal() throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    byte[] plaintext =
        vector.path("canonical_plaintext_utf8").textValue().getBytes(StandardCharsets.UTF_8);
    for (String field :
        List.of(
            "v",
            "realmId",
            "operationId",
            "command",
            "method",
            "route",
            "accountId",
            "tenantId",
            "ownerClient",
            "ownerSubject",
            "originDigest",
            "workflowId",
            "requestDigest",
            "expectedState",
            "authorityValidUntil",
            "representation")) {
      var missing = (ObjectNode) vector.path("metadata").deepCopy();
      missing.remove(field);
      refuses(() -> codec.sealSelfAppIntent(missing, plaintext));
    }
    var extra = (ObjectNode) vector.path("metadata").deepCopy();
    extra.put("authority", true);
    refuses(() -> codec.sealSelfAppIntent(extra, plaintext));
    var coerced = (ObjectNode) vector.path("metadata").deepCopy();
    coerced.put("tenantId", "7");
    refuses(() -> codec.sealSelfAppIntent(coerced, plaintext));
    coerced.put("tenantId", 7.0);
    refuses(() -> codec.sealSelfAppIntent(coerced, plaintext));
    coerced.put("tenantId", 7);
    coerced.putNull("ownerSubject");
    refuses(() -> codec.sealSelfAppIntent(coerced, plaintext));
  }

  @Test
  void closedEnvelopeRejectsVersionAliasesDuplicatesTrailingAndNoncanonicalEncodings()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    var metadata = vector.path("metadata");
    String original = vector.path("canonical_envelope_utf8").textValue();
    for (String wire :
        List.of(
            " " + original,
            original + " {}",
            original.replace("\"v\":1", "\"v\":1,\"v\":1"),
            original.replace("\"v\":1", "\"v\":2"),
            original.replace("\"v\":1", "\"v\":\"1\""),
            original.replace("\"v\":1", "\"v\":1.0"))) {
      refuses(() -> codec.openSelfAppIntent(metadata, wire));
    }
    var extra = codec.read(original, ObjectNode.class);
    extra.set("metadata", metadata);
    refuses(() -> codec.openSelfAppIntent(metadata, codec.canonical(extra)));
    for (String nonce : List.of("", "AA", "AA==", "A".repeat(17), "A".repeat(15) + "!")) {
      var malformed = codec.read(original, ObjectNode.class);
      malformed.put("nonce", nonce);
      refuses(() -> codec.openSelfAppIntent(metadata, codec.canonical(malformed)));
    }
    var shortTag = codec.read(original, ObjectNode.class);
    shortTag.put("ciphertext", "AA");
    refuses(() -> codec.openSelfAppIntent(metadata, codec.canonical(shortTag)));
    refuses(() -> codec.openSelfAppIntent(metadata, "x".repeat(87501)));
  }

  @Test
  void frozenCommandIsClosedAndBoundToTheCheckedMetadataBeforeEncryption() throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    var metadata = vector.path("metadata");
    String original = vector.path("canonical_plaintext_utf8").textValue();
    var unknown = codec.read(original, ObjectNode.class);
    unknown.put("humanBearer", "must-never-persist");
    refuses(
        () ->
            codec.sealSelfAppIntent(
                metadata, codec.canonical(unknown).getBytes(StandardCharsets.UTF_8)));
    var foreign = codec.read(original, ObjectNode.class);
    ((ObjectNode) foreign.path("body")).put("operationId", "44444444-4444-4444-8444-444444444444");
    refuses(
        () ->
            codec.sealSelfAppIntent(
                metadata, codec.canonical(foreign).getBytes(StandardCharsets.UTF_8)));
    var altered = codec.read(original, ObjectNode.class);
    ((ObjectNode) altered.path("body").path("target")).put("tenantId", "8");
    refuses(
        () ->
            codec.sealSelfAppIntent(
                metadata, codec.canonical(altered).getBytes(StandardCharsets.UTF_8)));
    byte[] noncanonical = (" " + original).getBytes(StandardCharsets.UTF_8);
    refuses(() -> codec.sealSelfAppIntent(metadata, noncanonical));
    refuses(() -> codec.sealSelfAppIntent(metadata, new byte[65537]));
  }

  @Test
  void newEncryptionUsesFreshNonceAndEachEnvelopeOpensOnlyWithItsOriginalMetadata()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    var metadata = vector.path("metadata");
    byte[] plaintext =
        vector.path("canonical_plaintext_utf8").textValue().getBytes(StandardCharsets.UTF_8);
    String first = codec.sealSelfAppIntent(metadata, plaintext);
    String second = codec.sealSelfAppIntent(metadata, plaintext);
    assertNotEquals(
        codec.read(first, JsonNode.class).path("nonce"),
        codec.read(second, JsonNode.class).path("nonce"));
    assertArrayEquals(plaintext, codec.openSelfAppIntent(metadata, first));
    assertArrayEquals(plaintext, codec.openSelfAppIntent(metadata, second));
    assertFalse(first.contains("\"secret\""));
    assertFalse(first.contains("\"initialCode\""));
  }

  @Test
  void privateLocalIncarnationRoundTripsWithoutChangingTheOriginalMetadataOrNativeBody()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    var command = codec.read(vector.path("canonical_plaintext_utf8").textValue(), ObjectNode.class);
    var body = command.path("body").deepCopy();
    var local = command.putObject("localState");
    local.put("v", 1);
    local.putNull("enrollmentOperationId");
    local.putNull("pendingEmailStartOperationId");
    local.putArray("invites").addObject().put("id", "1").put("status", "ACTIVE");
    byte[] bytes = codec.canonical(command).getBytes(StandardCharsets.UTF_8);
    String encrypted = codec.sealSelfAppIntent(vector.path("metadata"), bytes);
    assertArrayEquals(bytes, codec.openSelfAppIntent(vector.path("metadata"), encrypted));
    assertEquals(
        body,
        codec
            .read(
                codec.utf8(codec.openSelfAppIntent(vector.path("metadata"), encrypted)),
                JsonNode.class)
            .path("body"));
  }

  @Test
  void localIncarnationIsClosedAndExplicitNoneCannotBecomeMissingOrForeignAuthority()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    var command = codec.read(vector.path("canonical_plaintext_utf8").textValue(), ObjectNode.class);
    for (String local :
        List.of(
            "{}",
            "{\"v\":1,\"enrollmentOperationId\":null,\"pendingEmailStartOperationId\":null,\"invites\":[],\"authority\":true}",
            "{\"v\":1,\"enrollmentOperationId\":\"foreign\",\"pendingEmailStartOperationId\":null,\"invites\":[]}",
            "{\"v\":1,\"enrollmentOperationId\":null,\"pendingEmailStartOperationId\":null,\"invites\":[{\"id\":\"1\",\"status\":\"ACTIVE\"},{\"id\":\"1\",\"status\":\"ACTIVE\"}]}",
            "{\"v\":1,\"enrollmentOperationId\":null,\"pendingEmailStartOperationId\":null,\"invites\":[{\"id\":\"1\",\"status\":\"OWNER\"}]}")) {
      command.set("localState", codec.read(local, JsonNode.class));
      refuses(
          () ->
              codec.sealSelfAppIntent(
                  vector.path("metadata"),
                  codec.canonical(command).getBytes(StandardCharsets.UTF_8)));
    }
  }

  private JsonNode ordinaryVector() throws Exception {
    try (var input = getClass().getResourceAsStream("/user-maintenance-secret-intent-v1.json")) {
      assertNotNull(input);
      var fixture =
          new AccountLifecycleCodec(new byte[32])
              .read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
      return fixture.path("crypto_vectors").get(0);
    }
  }

  @Test
  void protectedSelfAssertionUsesTheSameBoundPrivateFormatWithoutGrantingAuthority()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    var metadata = (ObjectNode) vector.path("metadata").deepCopy();
    var command = codec.read(vector.path("canonical_plaintext_utf8").textValue(), ObjectNode.class);
    var origin = (ObjectNode) command.path("body").path("target").path("origin");
    origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
    metadata.put("originDigest", codec.digest("otp-origin", origin));
    byte[] plaintext = codec.canonical(command).getBytes(StandardCharsets.UTF_8);
    String encrypted = codec.sealSelfAppIntent(metadata, plaintext);
    assertArrayEquals(plaintext, codec.openSelfAppIntent(metadata, encrypted));
    var ordinaryMetadata = vector.path("metadata");
    refuses(() -> codec.openSelfAppIntent(ordinaryMetadata, encrypted));
  }

  @Test
  void privateSelfFormatStillRejectsEveryUnreviewedAuthorityEvenWithMatchingDigest()
      throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    for (String authority :
        List.of(
            "PLATFORM", "TENANT_PEER", "SELF", "true", "", "PROTECTED_ACCOUNT_OPERATIONS_V1 ")) {
      var metadata = (ObjectNode) vector.path("metadata").deepCopy();
      var command =
          codec.read(vector.path("canonical_plaintext_utf8").textValue(), ObjectNode.class);
      var origin = (ObjectNode) command.path("body").path("target").path("origin");
      origin.put("humanAuthority", authority);
      metadata.put("originDigest", codec.digest("otp-origin", origin));
      byte[] plaintext = codec.canonical(command).getBytes(StandardCharsets.UTF_8);
      refuses(() -> codec.sealSelfAppIntent(metadata, plaintext));
    }
  }

  private AccountLifecycleCodec vectorCodec() {
    return new AccountLifecycleCodec(
        HexFormat.of()
            .parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
  }

  @Test
  void frozenLocalAccountKindsRemainPrivateWithoutChangingTheNativeCommand() throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    for (String kind : List.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN")) {
      var metadata = (ObjectNode) vector.path("metadata").deepCopy();
      var command = codec.read(vector.path("canonical_plaintext_utf8").textValue(), ObjectNode.class);
      var origin = (ObjectNode) command.path("body").path("target").path("origin");
      if (kind.equals("TENANT_ADMIN") || kind.equals("PLATFORM_ADMIN"))
        origin.put("humanAuthority", "PROTECTED_ACCOUNT_OPERATIONS_V1");
      metadata.put("originDigest", codec.digest("otp-origin", origin));
      var nativeBody = command.path("body").deepCopy();
      var local = command.putObject("localAccount");
      local.put("kind", kind);
      local.put("consultant", kind.endsWith("ADMIN"));
      local.putNull("email");
      local.putNull("consultantEmail");
      byte[] bytes = codec.canonical(command).getBytes(StandardCharsets.UTF_8);
      String encrypted = codec.sealSelfAppIntent(metadata, bytes);
      assertArrayEquals(bytes, codec.openSelfAppIntent(metadata, encrypted));
      assertEquals(
          nativeBody,
          codec.read(codec.utf8(codec.openSelfAppIntent(metadata, encrypted)), JsonNode.class).path("body"));
      assertFalse(encrypted.contains("localAccount"));
    }
  }

  @Test
  void localAccountSnapshotRejectsUnknownKindsAuthorityDriftAndUnneededEmailData() throws Exception {
    var vector = ordinaryVector();
    var codec = vectorCodec();
    for (String local : List.of(
        "{}",
        "{\"kind\":\"SUPPORT\",\"consultant\":false,\"email\":null,\"consultantEmail\":null}",
        "{\"kind\":\"TENANT_ADMIN\",\"consultant\":false,\"email\":null,\"consultantEmail\":null}",
        "{\"kind\":\"USER\",\"consultant\":true,\"email\":null,\"consultantEmail\":null}",
        "{\"kind\":\"USER\",\"consultant\":\"false\",\"email\":null,\"consultantEmail\":null}",
        "{\"kind\":\"USER\",\"consultant\":false,\"email\":\"unneeded@oriso.org\",\"consultantEmail\":null}",
        "{\"kind\":\"USER\",\"consultant\":false,\"email\":null,\"consultantEmail\":null,\"authority\":true}")) {
      var command = codec.read(vector.path("canonical_plaintext_utf8").textValue(), ObjectNode.class);
      command.set("localAccount", codec.read(local, JsonNode.class));
      refuses(() -> codec.sealSelfAppIntent(vector.path("metadata"), codec.canonical(command).getBytes(StandardCharsets.UTF_8)));
    }
  }

  private void refuses(org.junit.jupiter.api.function.Executable operation) {
    var error = assertThrows(IllegalArgumentException.class, operation);
    assertEquals("Invalid owned secret intent", error.getMessage());
    assertNull(error.getCause());
  }

  private void independentVector(String name) throws Exception {
    var reader = new AccountLifecycleCodec(new byte[32]);
    JsonNode fixture;
    try (var input = getClass().getResourceAsStream("/user-maintenance-secret-intent-v1.json")) {
      assertNotNull(input);
      fixture =
          reader.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
    }
    JsonNode vector = null;
    for (var candidate : fixture.path("crypto_vectors"))
      if (name.equals(candidate.path("name").textValue())) vector = candidate;
    assertNotNull(vector);
    byte[] nonce = HexFormat.of().parseHex(vector.path("nonce_hex").textValue());
    var fixed =
        new SecureRandom() {
          @Override
          public void nextBytes(byte[] bytes) {
            assertEquals(12, bytes.length);
            System.arraycopy(nonce, 0, bytes, 0, bytes.length);
          }
        };
    var codec =
        new AccountLifecycleCodec(
            HexFormat.of().parseHex(fixture.path("synthetic_root_key_hex").textValue()),
            Clock.systemUTC(),
            fixed);
    for (var canonical : fixture.path("canonical_vectors"))
      assertEquals(
          canonical.path("canonical_utf8").textValue(), codec.canonical(canonical.path("input")));
    var metadata = vector.path("metadata");
    assertTrue(metadata.has("tenantId"));
    assertEquals(vector.path("canonical_metadata_utf8").textValue(), codec.canonical(metadata));
    byte[] plain =
        vector.path("canonical_plaintext_utf8").textValue().getBytes(StandardCharsets.UTF_8);
    String expected = vector.path("canonical_envelope_utf8").textValue();
    assertEquals(expected, codec.sealSelfAppIntent(metadata, plain));
    assertArrayEquals(plain, codec.openSelfAppIntent(metadata, expected));
  }
}
