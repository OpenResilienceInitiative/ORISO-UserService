package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProtectedInvitationMailSecretsTest {
  private final byte[] key =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private ProtectedInvitationMailSecrets secrets;
  private ProtectedInvitationMailSecrets.Metadata metadata;
  private JsonNode vector;
  private String mail;

  @BeforeEach
  void vector() throws Exception {
    vector =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-mail-v1.json")),
            JsonNode.class);
    metadata =
        json.read(
            json.canonical(vector.path("metadata")), ProtectedInvitationMailSecrets.Metadata.class);
    mail = json.canonical(vector.path("mail"));
    secrets = new ProtectedInvitationMailSecrets(key, metadata.realmId());
  }

  @Test
  void sealedMailUsesIndependentDerivedKeyAndAuthenticatedMetadata() throws Exception {
    String sealed = secrets.seal(metadata, mail);
    assertNotNull(sealed);
    assertFalse(sealed.contains(vector.path("mail").path("rawToken").asText()));
    assertFalse(sealed.contains("Willkommen"));
    JsonNode envelope = json.read(sealed, JsonNode.class);
    assertEquals(12, Base64.getUrlDecoder().decode(envelope.path("nonce").asText()).length);
    Cipher independent = Cipher.getInstance("AES/GCM/NoPadding");
    independent.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(
            HexFormat.of().parseHex(vector.path("derivedEncryptionKeyHex").asText()), "AES"),
        new GCMParameterSpec(128, Base64.getUrlDecoder().decode(envelope.path("nonce").asText())));
    independent.updateAAD(json.canonical(metadata).getBytes(StandardCharsets.UTF_8));
    assertEquals(
        mail,
        new String(
            independent.doFinal(
                Base64.getUrlDecoder().decode(envelope.path("ciphertext").asText())),
            StandardCharsets.UTF_8));
  }

  @Test
  void reconstructedWorkerOpensExactlyTheOriginalUtf8Payload() {
    String sealed = secrets.seal(metadata, mail);
    assertNotNull(sealed);
    assertEquals(
        mail, new ProtectedInvitationMailSecrets(key, metadata.realmId()).open(metadata, sealed));
    var large = (ObjectNode) vector.path("mail").deepCopy();
    large.put("body", "Ä".repeat(40_000));
    large.putNull("language");
    String largeMail = json.canonical(large);
    assertTrue(largeMail.getBytes(StandardCharsets.UTF_8).length > 65_536);
    var largeMetadata = metadataFor(large);
    String largeSealed = secrets.seal(largeMetadata, largeMail);
    assertNotNull(largeSealed);
    assertEquals(
        largeMail,
        new ProtectedInvitationMailSecrets(key, metadata.realmId())
            .open(largeMetadata, largeSealed));
  }

  @Test
  void everyOwnedHeaderFieldIsBoundToTheCiphertext() {
    String sealed = secrets.seal(metadata, mail);
    assertNotNull(sealed);
    ObjectNode original = (ObjectNode) vector.path("metadata");
    for (String field :
        new String[] {
          "v",
          "approvalId",
          "workflowId",
          "inviteId",
          "authorizationRevision",
          "scopeDigest",
          "requestDigest",
          "tokenHash",
          "payloadDigest"
        }) {
      ObjectNode changed = original.deepCopy();
      if (changed.path(field).isIntegralNumber())
        changed.put(field, changed.path(field).longValue() + 1);
      else if (field.endsWith("Id")) changed.put(field, "f53dd071-a5b9-486a-bb91-849901d77143");
      else changed.put(field, "b".repeat(64));
      var foreign =
          json.read(json.canonical(changed), ProtectedInvitationMailSecrets.Metadata.class);
      assertThrows(RuntimeException.class, () -> secrets.open(foreign, sealed), field);
    }
  }

  @Test
  void anotherRealmOrMasterKeyCannotOpenTheOriginalIntent() {
    String sealed = secrets.seal(metadata, mail);
    assertNotNull(sealed);
    assertThrows(
        RuntimeException.class,
        () ->
            new ProtectedInvitationMailSecrets(new byte[32], metadata.realmId())
                .open(metadata, sealed));
    assertThrows(
        RuntimeException.class,
        () -> new ProtectedInvitationMailSecrets(key, "another-realm").open(metadata, sealed));
  }

  @Test
  void independentNoncesAndAnyCiphertextChangeStaySafe() {
    String first = secrets.seal(metadata, mail), second = secrets.seal(metadata, mail);
    assertNotNull(first);
    assertNotNull(second);
    JsonNode a = json.read(first, JsonNode.class), b = json.read(second, JsonNode.class);
    assertNotEquals(a.path("nonce"), b.path("nonce"));
    var altered = (ObjectNode) a.deepCopy();
    byte[] bytes = Base64.getUrlDecoder().decode(a.path("ciphertext").asText());
    bytes[bytes.length - 1] ^= 1;
    altered.put("ciphertext", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    assertThrows(RuntimeException.class, () -> secrets.open(metadata, json.canonical(altered)));
  }

  @Test
  void aDifferentTokenOrContentCannotBeSealedUnderTheOriginalTuple() {
    var changed = (ObjectNode) vector.path("mail").deepCopy();
    String originalToken = changed.path("rawToken").asText();
    String anotherToken = "A".repeat(43);
    changed.put("rawToken", anotherToken);
    changed.put(
        "acceptUrl", changed.path("acceptUrl").asText().replace(originalToken, anotherToken));
    changed.put("body", changed.path("body").asText().replace(originalToken, anotherToken));
    var changedTokenMetadata = metadataFor(changed);
    assertEquals(metadata.tokenHash(), changedTokenMetadata.tokenHash());
    assertNotEquals(metadata.payloadDigest(), changedTokenMetadata.payloadDigest());
    assertThrows(
        RuntimeException.class, () -> secrets.seal(changedTokenMetadata, json.canonical(changed)));
    changed.setAll((ObjectNode) vector.path("mail"));
    changed.put("subject", "Changed");
    assertThrows(RuntimeException.class, () -> secrets.seal(metadata, json.canonical(changed)));
  }

  @Test
  void malformedDuplicateTrailingAndUnsupportedEnvelopesAreRefused() {
    String sealed = secrets.seal(metadata, mail);
    assertNotNull(sealed);
    assertThrows(RuntimeException.class, () -> secrets.open(metadata, sealed + " {}"));
    assertThrows(
        RuntimeException.class,
        () -> secrets.open(metadata, sealed.replaceFirst("\\{", "{\"v\":1,")));
    var changed = (ObjectNode) json.read(sealed, JsonNode.class);
    changed.put("v", 2);
    assertThrows(RuntimeException.class, () -> secrets.open(metadata, json.canonical(changed)));
    changed.put("v", 1);
    changed.put("extra", "ignored");
    assertThrows(RuntimeException.class, () -> secrets.open(metadata, json.canonical(changed)));
  }

  @Test
  void metadataTextDoesNotExposeInvitationOrTokenValues() {
    assertEquals("ProtectedMailMetadata[redacted]", metadata.toString());
    assertFalse(metadata.toString().contains(metadata.approvalId()));
    assertFalse(metadata.toString().contains(metadata.tokenHash()));
  }

  @Test
  void authenticatedUnknownOrMissingPayloadFieldsAreStillRejected() {
    var changed = (ObjectNode) vector.path("mail").deepCopy();
    changed.put("bearer", "synthetic-human-bearer");
    var extra = metadataFor(changed);
    assertThrows(RuntimeException.class, () -> secrets.seal(extra, json.canonical(changed)));
    changed.remove("bearer");
    changed.remove("language");
    var missing = metadataFor(changed);
    assertThrows(RuntimeException.class, () -> secrets.seal(missing, json.canonical(changed)));
  }

  @Test
  void noncanonicalAndOversizedInputsCannotEnterTheOutbox() {
    assertThrows(RuntimeException.class, () -> secrets.seal(metadata, mail + " "));
    var large = (ObjectNode) vector.path("mail").deepCopy();
    large.put("body", "x".repeat(1_048_576));
    String oversized = json.canonical(large);
    assertTrue(oversized.getBytes(StandardCharsets.UTF_8).length > 1_048_576);
    var largeMetadata = metadataFor(large);
    assertThrows(RuntimeException.class, () -> secrets.seal(largeMetadata, oversized));
    assertThrows(RuntimeException.class, () -> secrets.open(metadata, "x".repeat(1_572_865)));
  }

  private ProtectedInvitationMailSecrets.Metadata metadataFor(JsonNode payload) {
    var header = (ObjectNode) vector.path("metadata").deepCopy();
    header.put("payloadDigest", json.digest("protected-invitation-mail-payload/v1", payload));
    return json.read(json.canonical(header), ProtectedInvitationMailSecrets.Metadata.class);
  }
}
