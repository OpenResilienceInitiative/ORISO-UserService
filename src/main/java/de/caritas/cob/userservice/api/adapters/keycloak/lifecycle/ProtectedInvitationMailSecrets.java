package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Private at-rest mail data; decrypting an intent never grants dispatch authority. */
final class ProtectedInvitationMailSecrets {
  private static final int MAX_MAIL_BYTES = 1_048_576;
  private static final int MAX_ENVELOPE_BYTES = 1_572_864;
  private static final SecureRandom RANDOM = new SecureRandom();
  private final byte[] contextKey;
  private final String realmId;
  private final AccountLifecycleCodec canonical;
  private final ObjectMapper reader =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  ProtectedInvitationMailSecrets(byte[] contextKey, String realmId) {
    if (contextKey == null
        || contextKey.length < 32
        || realmId == null
        || realmId.isBlank()
        || realmId.length() > 512) throw unavailable();
    this.contextKey = contextKey.clone();
    this.realmId = realmId;
    canonical = new AccountLifecycleCodec(contextKey);
  }

  String seal(Metadata metadata, String canonicalMail) {
    byte[] derived = null;
    byte[] plaintext = null;
    try {
      validateMetadata(metadata);
      plaintext = bytes(canonicalMail, MAX_MAIL_BYTES);
      validateMail(metadata, canonicalMail);
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      derived = derive(metadata);
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, derived, nonce, metadata);
      return canonical.canonical(
          Map.of("v", 1, "nonce", encode(nonce), "ciphertext", encode(cipher.doFinal(plaintext))));
    } catch (Exception failure) {
      throw unavailable();
    } finally {
      clear(derived);
      clear(plaintext);
    }
  }

  String open(Metadata independentlyCheckedMetadata, String envelope) {
    byte[] derived = null;
    byte[] plaintext = null;
    try {
      validateMetadata(independentlyCheckedMetadata);
      bytes(envelope, MAX_ENVELOPE_BYTES);
      JsonNode value = read(envelope);
      fields(value, Set.of("v", "nonce", "ciphertext"));
      if (!value.path("v").isIntegralNumber()
          || !value.path("v").canConvertToInt()
          || value.path("v").intValue() != 1
          || !envelope.equals(canonical.canonical(value))) throw unavailable();
      byte[] nonce = decode(text(value, "nonce", 16));
      byte[] ciphertext = decode(text(value, "ciphertext", MAX_ENVELOPE_BYTES));
      if (nonce.length != 12
          || ciphertext.length < 16
          || ciphertext.length > MAX_MAIL_BYTES + 16) throw unavailable();
      derived = derive(independentlyCheckedMetadata);
      plaintext =
          cipher(Cipher.DECRYPT_MODE, derived, nonce, independentlyCheckedMetadata)
              .doFinal(ciphertext);
      if (plaintext.length > MAX_MAIL_BYTES) throw unavailable();
      String mail =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(plaintext))
              .toString();
      validateMail(independentlyCheckedMetadata, mail);
      return mail;
    } catch (Exception failure) {
      throw unavailable();
    } finally {
      clear(derived);
      clear(plaintext);
    }
  }

  private void validateMetadata(Metadata metadata) {
    if (metadata == null
        || metadata.v() != 1
        || !realmId.equals(metadata.realmId())
        || metadata.inviteId() <= 0
        || metadata.authorizationRevision() <= 0) throw unavailable();
    uuid(metadata.approvalId());
    uuid(metadata.workflowId());
    for (String digest :
        new String[] {
          metadata.scopeDigest(), metadata.requestDigest(), metadata.tokenHash(), metadata.payloadDigest()
        }) {
      if (digest == null || !digest.matches("[0-9a-f]{64}")) throw unavailable();
    }
  }

  private void validateMail(Metadata metadata, String mail) throws Exception {
    JsonNode value = read(mail);
    fields(value, Set.of("rawToken", "acceptUrl", "subject", "body", "templateId", "language"));
    if (!mail.equals(canonical.canonical(value))
        || !value.path("templateId").isIntegralNumber()
        || !value.path("templateId").canConvertToLong()
        || value.path("templateId").longValue() <= 0) throw unavailable();
    String token = text(value, "rawToken", 43);
    if (decode(token).length != 32
        || !equal(metadata.tokenHash(), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))))
        || !equal(metadata.payloadDigest(), canonical.digest("protected-invitation-mail-payload/v1", value)))
      throw unavailable();
    String url = text(value, "acceptUrl", 8192);
    var uri = java.net.URI.create(url);
    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !uri.getRawPath().endsWith("/" + token)) throw unavailable();
    text(value, "subject", MAX_MAIL_BYTES);
    text(value, "body", MAX_MAIL_BYTES);
    if (!value.path("language").isNull()) text(value, "language", 64);
  }

  private byte[] derive(Metadata metadata) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(contextKey, "HmacSHA256"));
    return mac.doFinal(
        ("protected-invitation-mail-encryption/v1\n" + canonical.canonical(metadata))
            .getBytes(StandardCharsets.UTF_8));
  }

  private Cipher cipher(int mode, byte[] key, byte[] nonce, Metadata metadata) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD(canonical.canonical(metadata).getBytes(StandardCharsets.UTF_8));
    return cipher;
  }

  private JsonNode read(String value) throws Exception {
    JsonNode parsed = reader.readTree(value);
    if (parsed == null) throw unavailable();
    return parsed;
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (!value.isObject()) throw unavailable();
    var actual = new HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw unavailable();
  }

  private static String text(JsonNode value, String field, int max) {
    JsonNode node = value.path(field);
    if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max)
      throw unavailable();
    return node.textValue();
  }

  private static byte[] bytes(String value, int max) {
    if (value == null || value.length() > max) throw unavailable();
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > max) throw unavailable();
    return encoded;
  }

  private static byte[] decode(String value) {
    if (!value.matches("[A-Za-z0-9_-]+")) throw unavailable();
    byte[] decoded = Base64.getUrlDecoder().decode(value);
    if (!encode(decoded).equals(value)) throw unavailable();
    return decoded;
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static boolean equal(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
  }

  private static void uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) throw unavailable();
  }

  private static void clear(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Protected invitation mail unavailable");
  }

  record Metadata(
      int v,
      String realmId,
      String approvalId,
      String workflowId,
      long inviteId,
      long authorizationRevision,
      String scopeDigest,
      String requestDigest,
      String tokenHash,
      String payloadDigest) {
    @Override
    public String toString() {
      return "ProtectedMailMetadata[redacted]";
    }
  }
}
