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
final class ClaimedPasswordIntentCodec {
  private final AccountLifecycleCodec codec;
  private final Function<String, byte[]> rootMac;
  private final SecureRandom random = new SecureRandom();

  ClaimedPasswordIntentCodec(AccountLifecycleCodec codec, Function<String, byte[]> rootMac) {
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
      String claimId,
      long claimVersion,
      String workflowKind,
      String workflowDigest,
      String requestDigest,
      String expectedState,
      long authorityValidUntil,
      String representation) {
    @Override
    public String toString() {
      return "ClaimedPasswordMetadata[redacted]";
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
            "claimId",
            "claimVersion",
            "workflowKind",
            "workflowDigest",
            "requestDigest",
            "expectedState",
            "authorityValidUntil",
            "representation"));
    if (positive(value, "v") != 1
        || !"account.password-reset".equals(text(value, "command", 64))
        || !"POST".equals(text(value, "method", 16))
        || !"oriso-account-maintenance/v1/accounts/password-reset".equals(text(value, "route", 128))
        || !"FROZEN_CLAIMED_PASSWORD_HTTP_V1".equals(text(value, "representation", 64)))
      throw invalid();
    String kind = text(value, "workflowKind", 32);
    if (!Set.of("EXISTING_SETUP", "PASSWORD_RESET").contains(kind)) throw invalid();
    var tenant = value.get("tenantId");
    Long tenantId = null;
    if (!tenant.isNull()) {
      if (!tenant.isIntegralNumber() || !tenant.canConvertToLong() || tenant.longValue() < 0)
        throw invalid();
      tenantId = tenant.longValue();
    }
    return new Metadata(
        1,
        text(value, "realmId", 512),
        uuid(value, "operationId"),
        "account.password-reset",
        "POST",
        "oriso-account-maintenance/v1/accounts/password-reset",
        uuid(value, "accountId"),
        tenantId,
        text(value, "ownerClient", 255),
        uuid(value, "ownerSubject"),
        digest(value, "originDigest"),
        uuid(value, "claimId"),
        positive(value, "claimVersion"),
        kind,
        digest(value, "workflowDigest"),
        digest(value, "requestDigest"),
        digest(value, "expectedState"),
        positive(value, "authorityValidUntil"),
        "FROZEN_CLAIMED_PASSWORD_HTTP_V1");
  }

  private byte[] command(Metadata metadata, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 65536) throw invalid();
    byte[] snapshot = bytes.clone();
    String raw = codec.utf8(snapshot);
    var wrapper = codec.read(raw, JsonNode.class);
    fields(wrapper, Set.of("body", "localState"));
    var local = wrapper.path("localState");
    fields(local, Set.of("primaryKind", "primaryVersion", "consultantVersion"));
    if (!Set.of("USER", "CONSULTANT", "PLATFORM_ADMIN", "TENANT_ADMIN", "AGENCY_ADMIN")
        .contains(text(local, "primaryKind", 32))) throw invalid();
    digest(local, "primaryVersion");
    if (!local.get("consultantVersion").isNull()) digest(local, "consultantVersion");
    var body = wrapper.path("body");
    fields(body, Set.of("target", "workflow", "operationId", "expectedState", "change"));
    if (!metadata.operationId().equals(uuid(body, "operationId"))
        || !metadata.expectedState().equals(digest(body, "expectedState"))) throw invalid();
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
    if (!"PASSWORD_RESET".equals(text(origin, "kind", 32))
        || !metadata.claimId().equals(uuid(origin, "workflowId"))
        || !origin.get("humanSubject").isNull()
        || !origin.get("humanIssuer").isNull()
        || !origin.get("humanClient").isNull()
        || !origin.get("humanAuthority").isNull()
        || !metadata.originDigest().equals(codec.digest("maintenance-origin", origin)))
      throw invalid();
    var workflow = body.path("workflow");
    fields(
        workflow,
        Set.of(
            "v",
            "kind",
            "claimId",
            "claimVersion",
            "authorityValidUntil",
            "expectedUsername",
            "expectedEmailDigest",
            "profileKind",
            "profileId",
            "profileVersion"));
    if (positive(workflow, "v") != 1
        || !metadata.workflowKind().equals(text(workflow, "kind", 32))
        || !metadata.claimId().equals(uuid(workflow, "claimId"))
        || metadata.claimVersion() != positive(workflow, "claimVersion")
        || metadata.authorityValidUntil() != positive(workflow, "authorityValidUntil")
        || !Set.of("ORDINARY", "PROTECTED").contains(text(workflow, "profileKind", 16))
        || !metadata.workflowDigest().equals(codec.digest("password-reset-workflow/v1", workflow)))
      throw invalid();
    text(workflow, "expectedUsername", 255);
    digest(workflow, "expectedEmailDigest");
    text(workflow, "profileId", 255);
    positive(workflow, "profileVersion");
    var change = body.path("change");
    fields(change, Set.of("password", "temporary"));
    text(change, "password", 4096);
    if (!change.path("temporary").isBoolean()
        || change.path("temporary").booleanValue()
        || !raw.equals(codec.canonical(wrapper))) throw invalid();
    return snapshot;
  }

  private static long positive(JsonNode value, String name) {
    var number = value.path(name);
    if (!number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() <= 0)
      throw invalid();
    return number.longValue();
  }

  private byte[] cipher(int mode, Metadata metadata, byte[] nonce, byte[] input) {
    byte[] key =
        rootMac.apply(
            "oriso/claimed-password-intent/encryption/v1\n"
                + codec.digest("oriso/claimed-password-intent/metadata/v1", metadata));
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
    return new IllegalArgumentException("Invalid private claimed password intent");
  }
}
