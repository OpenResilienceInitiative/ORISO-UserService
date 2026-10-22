package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Data protection only. The locked private owner independently checks every metadata binding. */
final class SelfPasswordIntentCodec {
  private final AccountLifecycleCodec codec;
  private final Function<String, byte[]> rootMac;
  private final SecureRandom random = new SecureRandom();

  SelfPasswordIntentCodec(AccountLifecycleCodec codec, Function<String, byte[]> rootMac) {
    this.codec = codec;
    this.rootMac = rootMac;
  }

  String seal(JsonNode checkedMetadata, byte[] command) {
    try {
      var metadata = metadata(checkedMetadata);
      byte[] plaintext = command(metadata, command);
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      return codec.canonical(
          Map.of(
              "v",
              1,
              "nonce",
              encode(nonce),
              "ciphertext",
              encode(cipher(Cipher.ENCRYPT_MODE, metadata, nonce, plaintext))));
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  byte[] open(JsonNode checkedMetadata, String envelope) {
    try {
      var metadata = metadata(checkedMetadata);
      if (envelope == null || envelope.length() > 87500) throw invalid();
      var value = codec.read(envelope, JsonNode.class);
      fields(value, Set.of("v", "nonce", "ciphertext"));
      if (!value.path("v").isIntegralNumber()
          || !value.path("v").canConvertToInt()
          || value.path("v").intValue() != 1
          || !envelope.equals(codec.canonical(value))) throw invalid();
      byte[] nonce = decode(value.path("nonce"), 12, 12);
      byte[] ciphertext = decode(value.path("ciphertext"), 16, 65552);
      return command(metadata, cipher(Cipher.DECRYPT_MODE, metadata, nonce, ciphertext));
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private record Metadata(
      int v,
      String realmId,
      String operationId,
      String command,
      String method,
      String route,
      String accountId,
      Long tenantId,
      String ownerClient,
      String ownerSubject,
      String originDigest,
      String workflowId,
      String requestDigest,
      String expectedState,
      long authorityValidUntil,
      String representation) {
    @Override
    public String toString() {
      return "PasswordMetadata[redacted]";
    }
  }

  private Metadata metadata(JsonNode value) {
    fields(
        value,
        Set.of(
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
            "representation"));
    if (!value.path("v").isIntegralNumber()
        || !value.path("v").canConvertToInt()
        || value.path("v").intValue() != 1
        || !"account.self-password".equals(text(value, "command", 64))
        || !"POST".equals(text(value, "method", 16))
        || !"oriso-account-maintenance/v1/accounts/self-password".equals(text(value, "route", 128))
        || !"FROZEN_PASSWORD_HTTP_V1".equals(text(value, "representation", 64))) throw invalid();
    String operation = uuid(value, "operationId");
    if (!operation.equals(uuid(value, "workflowId"))) throw invalid();
    var tenant = value.get("tenantId");
    Long tenantId = null;
    if (!tenant.isNull()) {
      if (!tenant.isIntegralNumber() || !tenant.canConvertToLong() || tenant.longValue() < 0)
        throw invalid();
      tenantId = tenant.longValue();
    }
    var expiry = value.path("authorityValidUntil");
    if (!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue() <= 0)
      throw invalid();
    return new Metadata(
        1,
        text(value, "realmId", 512),
        operation,
        "account.self-password",
        "POST",
        "oriso-account-maintenance/v1/accounts/self-password",
        uuid(value, "accountId"),
        tenantId,
        text(value, "ownerClient", 255),
        uuid(value, "ownerSubject"),
        digest(value, "originDigest"),
        operation,
        digest(value, "requestDigest"),
        digest(value, "expectedState"),
        expiry.longValue(),
        "FROZEN_PASSWORD_HTTP_V1");
  }

  private byte[] command(Metadata metadata, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 65536) throw invalid();
    byte[] snapshot = bytes.clone();
    String raw = codec.utf8(snapshot);
    var wrapper = codec.read(raw, JsonNode.class);
    fields(wrapper, Set.of("body", "localSetup"));
    var local = wrapper.path("localSetup");
    fields(local, Set.of("kind", "updateDate", "passwordChangeRequired"));
    String kind = text(local, "kind", 16);
    if ("USER".equals(kind)) {
      if (!local.get("updateDate").isNull() || !local.get("passwordChangeRequired").isNull())
        throw invalid();
    } else if ("CONSULTANT".equals(kind)) {
      text(local, "updateDate", 128);
      if (!local.path("passwordChangeRequired").isBoolean()) throw invalid();
    } else throw invalid();
    var body = wrapper.path("body");
    fields(body, Set.of("target", "operationId", "expectedState", "change", "authorityValidUntil"));
    if (!metadata.operationId().equals(uuid(body, "operationId"))
        || !metadata.expectedState().equals(digest(body, "expectedState"))
        || !body.path("authorityValidUntil").isIntegralNumber()
        || !body.path("authorityValidUntil").canConvertToLong()
        || metadata.authorityValidUntil() != body.path("authorityValidUntil").longValue())
      throw invalid();
    var target = body.path("target");
    fields(target, Set.of("accountId", "tenantId", "origin"));
    if (!metadata.accountId().equals(uuid(target, "accountId"))
        || (metadata.tenantId() == null
            ? !target.get("tenantId").isNull()
            : !target.path("tenantId").isTextual()
                || !metadata.tenantId().toString().equals(target.path("tenantId").textValue())))
      throw invalid();
    var origin = target.path("origin");
    fields(
        origin,
        Set.of(
            "kind", "workflowId", "humanSubject", "humanIssuer", "humanClient", "humanAuthority"));
    if (!"SELF".equals(text(origin, "kind", 16))
        || !origin.get("humanAuthority").isNull()
        || !metadata.workflowId().equals(uuid(origin, "workflowId"))
        || !metadata.accountId().equals(uuid(origin, "humanSubject"))
        || !metadata.originDigest().equals(codec.digest("maintenance-origin", origin)))
      throw invalid();
    text(origin, "humanIssuer", 2048);
    text(origin, "humanClient", 255);
    var change = body.path("change");
    fields(change, Set.of("currentPassword", "password", "temporary"));
    String current = text(change, "currentPassword", 4096), chosen = text(change, "password", 4096);
    if (!change.path("temporary").isBoolean()
        || change.path("temporary").booleanValue()
        || current.equals(chosen)) throw invalid();
    if (!raw.equals(codec.canonical(wrapper))) throw invalid();
    return snapshot;
  }

  private byte[] cipher(int mode, Metadata metadata, byte[] nonce, byte[] input) {
    byte[] key =
        rootMac.apply(
            "oriso/user-maintenance-intent/encryption/v1\n"
                + codec.digest("oriso/user-maintenance-intent/metadata/v1", metadata));
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(codec.canonical(metadata).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (java.security.GeneralSecurityException failure) {
      throw invalid();
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (!value.isObject() || value.size() != expected.size()) throw invalid();
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!expected.equals(actual)) throw invalid();
  }

  private static String text(JsonNode value, String field, int max) {
    var v = value.path(field);
    if (!v.isTextual()
        || v.textValue().isBlank()
        || v.textValue().length() > max
        || v.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return v.textValue();
  }

  private static String uuid(JsonNode value, String field) {
    String v = text(value, field, 36);
    if (!UUID.fromString(v).toString().equals(v)) throw invalid();
    return v;
  }

  private static String digest(JsonNode value, String field) {
    String v = text(value, field, 64);
    if (!v.matches("[a-f0-9]{64}")) throw invalid();
    return v;
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] decode(JsonNode node, int min, int max) {
    if (!node.isTextual() || !node.textValue().matches("[A-Za-z0-9_-]+")) throw invalid();
    byte[] result = Base64.getUrlDecoder().decode(node.textValue());
    if (result.length < min || result.length > max || !node.textValue().equals(encode(result)))
      throw invalid();
    return result;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid private password intent");
  }
}
