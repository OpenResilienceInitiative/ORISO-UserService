package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Independent JCA custody proof only. A seal grants no actual User-row provenance or authority. */
class ReactivationIntentCodecTest {
  private static final byte[] KEY =
      "synthetic-reactivation-custody-key".getBytes(StandardCharsets.UTF_8);
  private static final String ACCOUNT = "85000000-0000-4000-8000-000000000001",
      OP = "85000000-0000-4000-8000-000000000002",
      HUMAN = "85000000-0000-4000-8000-000000000003";
  private final ObjectMapper json = new ObjectMapper();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);

  @Test
  void exactReactivationCipherRoundTripsIndependentJcaAndKeepsAllOriginalOwnershipMetadata()
      throws Exception {
    var metadata = metadata(false);
    byte[] frozen = plaintext(false);
    String sealed = assertDoesNotThrow(() -> codec.sealReactivationIntent(metadata, frozen));
    assertFalse(sealed.contains("synthetic-chosen-password"));
    var envelope = json.readTree(sealed);
    assertEquals(Set.of("v", "nonce", "ciphertext"), names(envelope));
    assertEquals(1, envelope.path("v").intValue());
    assertArrayEquals(frozen, independent(metadata, envelope));
    assertArrayEquals(frozen, codec.openReactivationIntent(metadata, sealed));
    assertArrayEquals(
        frozen, codec.openReactivationIntent(metadata, independentSeal(metadata, frozen)));
    assertEquals(16, metadata.size());
    assertEquals(29, json.readTree(frozen).path("originalSql").size());
    for (String field : names(metadata)) {
      var missing = metadata.deepCopy();
      missing.remove(field);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.openReactivationIntent(missing, sealed),
          field);
    }
    for (String field : List.of("operationId", "accountId", "ownerSubject", "workflowId")) {
      var changed = metadata.deepCopy();
      changed.put(field, "85000000-0000-4000-8000-000000000099");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.openReactivationIntent(changed, sealed),
          field);
    }
    for (String field : List.of("originDigest", "requestDigest", "expectedState")) {
      var changed = metadata.deepCopy();
      changed.put(field, "f".repeat(64));
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.openReactivationIntent(changed, sealed),
          field);
    }
    var expiry = metadata.deepCopy();
    expiry.put("authorityValidUntil", 32503680001L);
    assertThrows(
        IllegalArgumentException.class, () -> codec.openReactivationIntent(expiry, sealed));
    var tamper = envelope.deepCopy();
    String cipher = tamper.path("ciphertext").textValue();
    ((ObjectNode) tamper)
        .put("ciphertext", (cipher.charAt(0) == 'A' ? "B" : "A") + cipher.substring(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.openReactivationIntent(metadata, codec.canonical(tamper)));
  }

  @Test
  void selfAndReactivationEntrypointsRejectCrossPurposeOrUnownedBodyAndSqlShapes()
      throws Exception {
    var metadata = metadata(false);
    byte[] frozen = plaintext(false);
    String sealed = assertDoesNotThrow(() -> codec.sealReactivationIntent(metadata, frozen));
    var self = metadata(true);
    byte[] selfBody = plaintext(true);
    String selfSealed = assertDoesNotThrow(() -> codec.sealSelfPasswordIntent(self, selfBody));
    assertArrayEquals(selfBody, codec.openSelfPasswordIntent(self, selfSealed));
    assertThrows(
        IllegalArgumentException.class, () -> codec.openSelfPasswordIntent(metadata, sealed));
    assertThrows(
        IllegalArgumentException.class, () -> codec.sealSelfPasswordIntent(metadata, frozen));
    assertThrows(
        IllegalArgumentException.class, () -> codec.openReactivationIntent(self, selfSealed));
    assertThrows(
        IllegalArgumentException.class, () -> codec.sealReactivationIntent(self, selfBody));
    assertThrows(
        IllegalArgumentException.class, () -> codec.openReactivationIntent(metadata, selfSealed));
    for (var replacement :
        Map.of(
                "method",
                "PATCH",
                "route",
                "oriso-account-maintenance/v1/accounts/password-reset",
                "representation",
                "FROZEN_PASSWORD_HTTP_V1",
                "command",
                "account.self-password")
            .entrySet()) {
      var wrong = metadata.deepCopy();
      wrong.put(replacement.getKey(), replacement.getValue());
      assertThrows(
          IllegalArgumentException.class, () -> codec.sealReactivationIntent(wrong, frozen));
    }
    var original = (ObjectNode) json.readTree(frozen);
    for (String field : names(original.path("originalSql"))) {
      var missing = original.deepCopy();
      ((ObjectNode) missing.path("originalSql")).remove(field);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.sealReactivationIntent(metadata, bytes(missing)),
          field);
    }
    for (String extra : List.of("password", "mobile_token", "rawBearer", "frozen_intent")) {
      var added = original.deepCopy();
      ((ObjectNode) added.path("originalSql")).put(extra, "synthetic-forbidden");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.sealReactivationIntent(metadata, bytes(added)));
    }
    for (String key : List.of("user_id", "reactivation_operation_id")) {
      var wrong = original.deepCopy();
      ((ObjectNode) wrong.path("originalSql")).put(key, "85000000-0000-4000-8000-000000000099");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.sealReactivationIntent(metadata, bytes(wrong)));
    }
    for (var replacement :
        Map.of(
                "deletion_lifecycle_state",
                "ACTIVE",
                "create_date",
                "2026-10-22T00:00:00.000000001Z")
            .entrySet()) {
      var wrong = original.deepCopy();
      ((ObjectNode) wrong.path("originalSql")).put(replacement.getKey(), replacement.getValue());
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.sealReactivationIntent(metadata, bytes(wrong)));
    }
    var wrong = original.deepCopy();
    ((ObjectNode) wrong.path("body")).put("authorityValidUntil", 32503680000L);
    assertThrows(
        IllegalArgumentException.class, () -> codec.sealReactivationIntent(metadata, bytes(wrong)));
    var restore = original.deepCopy();
    ((ObjectNode) restore.path("body").path("change")).put("restoreEnabled", true);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.sealReactivationIntent(metadata, bytes(restore)));
  }

  @Test
  void encodedNativeUsernameAndSameMailboxBindBothExactSqlAndNativeSpellings() throws Exception {
    var metadata = metadata(false);
    var original = (ObjectNode) json.readTree(plaintext(false));
    var transcoder = new de.caritas.cob.userservice.api.helper.UsernameTranscoder();
    String sqlUsername = transcoder.encodeUsername("soft-deleted-asker");
    String nativeUsername = sqlUsername.toLowerCase(Locale.ROOT);
    ((ObjectNode) original.path("originalSql"))
        .put("username", sqlUsername)
        .put("email", "MiXeD@example.test");
    ((ObjectNode) original.path("body").path("change"))
        .put("expectedUsername", nativeUsername)
        .put("expectedEmail", "MiXeD@example.test");
    byte[] frozen = bytes(original);
    String sealed = assertDoesNotThrow(() -> codec.sealReactivationIntent(metadata, frozen));
    assertArrayEquals(frozen, codec.openReactivationIntent(metadata, sealed));
    assertArrayEquals(frozen, independent(metadata, json.readTree(sealed)));
    for (String foreign :
        List.of("soft-deleted-asker", transcoder.encodeUsername("foreign-asker"))) {
      var wrong = original.deepCopy();
      ((ObjectNode) wrong.path("body").path("change")).put("expectedUsername", foreign);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.sealReactivationIntent(metadata, bytes(wrong)));
    }
    var nativeEmailCase = original.deepCopy();
    ((ObjectNode) nativeEmailCase.path("body").path("change"))
        .put("expectedEmail", "mixed@example.test");
    byte[] nativeFrozen = bytes(nativeEmailCase);
    String nativeSealed =
        assertDoesNotThrow(() -> codec.sealReactivationIntent(metadata, nativeFrozen));
    assertArrayEquals(nativeFrozen, codec.openReactivationIntent(metadata, nativeSealed));
    assertArrayEquals(nativeFrozen, independent(metadata, json.readTree(nativeSealed)));
    assertEquals(
        "MiXeD@example.test", nativeEmailCase.path("originalSql").path("email").textValue());
    assertEquals(
        "mixed@example.test",
        nativeEmailCase.path("body").path("change").path("expectedEmail").textValue());
    var wrongEmail = original.deepCopy();
    ((ObjectNode) wrongEmail.path("body").path("change"))
        .put("expectedEmail", "unrelated@example.test");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.sealReactivationIntent(metadata, bytes(wrongEmail)));
    for (String field : List.of("expectedUsername", "expectedEmail")) {
      var nontextual = original.deepCopy();
      ((ObjectNode) nontextual.path("body").path("change")).put(field, 1);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.sealReactivationIntent(metadata, bytes(nontextual)));
    }
  }

  @Test
  void expiredReactivationHistoryAuthenticatesItsPhaseAndOriginalBodyWithoutRetainingPassword()
      throws Exception {
    var metadata = metadata(false);
    long expiry = java.time.Instant.parse("2026-10-23T12:00:00Z").getEpochSecond();
    metadata.put("authorityValidUntil", expiry);
    var retained =
        new AccountLifecycleCodec(
            KEY,
            java.time.Clock.fixed(
                java.time.Instant.ofEpochSecond(expiry + 604800), java.time.ZoneOffset.UTC));
    var original = json.readTree(plaintext(false));
    String sealed = retained.sealReactivationIntent(metadata, bytes(original));
    var actor =
        new de.caritas.cob.userservice.api.config.auth.TaskActorCredentials(
            de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN,
            "backend-account-admin",
            "synthetic-only",
            "85000000-0000-4000-8000-000000000004");
    for (int outcome : List.of(0, 200, 400, 403, 409)) {
      String phase = outcome == 0 ? "FROZEN" : outcome == 200 ? "ACKNOWLEDGED" : "NO_EFFECT";
      Integer refusalStatus = outcome >= 400 ? outcome : null;
      String receipt = "FROZEN".equals(phase) ? null : historyReceipt(original.path("body"), refusalStatus);
      String history =
          assertDoesNotThrow(
              () -> retained.retainReactivationHistory(metadata, sealed, phase, receipt, actor));
      assertNotEquals(sealed, history);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.retainReactivationHistory(
                  metadata,
                  sealed,
                  phase,
                  receipt,
                  new de.caritas.cob.userservice.api.config.auth.TaskActorCredentials(
                      de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN,
                      "foreign-admin",
                      "synthetic-only",
                      "85000000-0000-4000-8000-000000000004")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.verifyReactivationHistory(
                  metadata,
                  history,
                  phase + "_HISTORY",
                  receipt,
                  new de.caritas.cob.userservice.api.config.auth.TaskActorCredentials(
                      de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN,
                      "backend-account-admin",
                      "synthetic-only",
                      HUMAN)));
      var changedMetadata = metadata.deepCopy();
      changedMetadata.put("authorityValidUntil", expiry + 1);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.verifyReactivationHistory(
                  changedMetadata, history, phase + "_HISTORY", receipt, actor));
      var historyOuter = (ObjectNode) json.readTree(history);
      String ciphertext = historyOuter.path("ciphertext").textValue();
      historyOuter.put(
          "ciphertext", (ciphertext.charAt(0) == 'A' ? "B" : "A") + ciphertext.substring(1));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.verifyReactivationHistory(
                  metadata, codec.canonical(historyOuter), phase + "_HISTORY", receipt, actor));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.verifyReactivationHistory(
                  metadata,
                  history,
                  phase + "_HISTORY",
                  "FROZEN".equals(phase) ? "{}" : null,
                  actor));
      var privateHistory = json.readTree(independent(metadata, json.readTree(history)));
      assertEquals(
          Set.of("phase", "originalSql", "template", "intentDigest"), names(privateHistory));
      assertEquals(phase, privateHistory.path("phase").textValue());
      assertEquals(original.path("originalSql"), privateHistory.path("originalSql"));
      assertEquals(29, privateHistory.path("originalSql").size());
      assertFalse(privateHistory.path("template").path("change").has("password"));
      assertFalse(codec.canonical(privateHistory).contains("synthetic-chosen-password"));
      assertEquals(
          codec.digest("maintenance-intent", original.path("body")),
          privateHistory.path("intentDigest").textValue());
      var verified =
          retained.verifyReactivationHistory(metadata, history, phase + "_HISTORY", receipt, actor);
      assertEquals(phase, verified.phase());
      assertEquals(original.path("originalSql"), verified.originalSql());
      assertEquals(privateHistory.path("template"), verified.template());
      assertEquals(privateHistory.path("intentDigest").textValue(), verified.intentDigest());
      assertEquals(
          codec.canonical(original.path("body")),
          retained.materializeReactivationHistory(
              metadata, history, phase + "_HISTORY", receipt, actor, "synthetic-chosen-password"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.materializeReactivationHistory(
                  metadata, history, phase + "_HISTORY", receipt, actor, "different-password"));
      assertThrows(
          IllegalArgumentException.class, () -> retained.openReactivationIntent(metadata, history));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.verifyReactivationHistory(
                  metadata, sealed, phase + "_HISTORY", receipt, actor));
      for (String name : names(privateHistory)) {
        var missing = (ObjectNode) privateHistory.deepCopy();
        missing.remove(name);
        String forged = independentSeal(metadata, bytes(missing));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                retained.verifyReactivationHistory(
                    metadata, forged, phase + "_HISTORY", receipt, actor),
            name);
      }
      for (String scenario :
          List.of(
              "extra", "phase", "digest", "missing-sql-field", "template-operation", "password")) {
        var changed = (ObjectNode) privateHistory.deepCopy();
        switch (scenario) {
          case "extra" -> changed.put("authority", true);
          case "phase" -> changed.put("phase", "FROZEN".equals(phase) ? "ACKNOWLEDGED" : "FROZEN");
          case "digest" -> changed.put("intentDigest", "f".repeat(64));
          case "missing-sql-field" ->
              ((ObjectNode) changed.path("originalSql")).remove("create_date");
          case "template-operation" ->
              ((ObjectNode) changed.path("template")).put("operationId", HUMAN);
          case "password" ->
              ((ObjectNode) changed.path("template").path("change"))
                  .put("password", "synthetic-chosen-password");
        }
        String forged = independentSeal(metadata, bytes(changed));
        if ("FROZEN".equals(phase) && "digest".equals(scenario)) {
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  retained.materializeReactivationHistory(
                      metadata,
                      forged,
                      phase + "_HISTORY",
                      null,
                      actor,
                      "synthetic-chosen-password"));
        } else {
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  retained.verifyReactivationHistory(
                      metadata, forged, phase + "_HISTORY", receipt, actor),
              scenario);
        }
      }
      assertThrows(
          IllegalArgumentException.class,
          () ->
              retained.verifyReactivationHistory(
                  metadata,
                  history,
                  "FROZEN".equals(phase) ? "ACKNOWLEDGED_HISTORY" : "FROZEN_HISTORY",
                  receipt,
                  actor));
      if (!"FROZEN".equals(phase)) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                retained.verifyReactivationHistory(
                    metadata, history, phase + "_HISTORY", "{}", actor));
        var foreign = (ObjectNode) original.path("body").deepCopy();
        foreign.put("operationId", HUMAN);
        String wrongReceipt = historyReceipt(foreign, refusalStatus);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                retained.verifyReactivationHistory(
                    metadata, history, phase + "_HISTORY", wrongReceipt, actor));
      } else {
        String forgedTerminal = historyReceipt(original.path("body"), null);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                retained.verifyReactivationHistory(
                    metadata, history, "ACKNOWLEDGED_HISTORY", forgedTerminal, actor));
      }
    }
    var before =
        new AccountLifecycleCodec(
            KEY,
            java.time.Clock.fixed(
                java.time.Instant.ofEpochSecond(expiry + 604799), java.time.ZoneOffset.UTC));
    assertThrows(
        IllegalArgumentException.class,
        () -> before.retainReactivationHistory(metadata, sealed, "FROZEN", null, actor));
  }

  private String historyReceipt(JsonNode body, Integer status) throws Exception {
    var payload = new TreeMap<String, Object>();
    payload.put("realmId", "synthetic-realm");
    payload.put("accountId", ACCOUNT);
    payload.put("operationId", body.path("operationId").textValue());
    payload.put("command", "reactivate");
    payload.put("ownerClient", "backend-account-admin");
    payload.put("ownerSubject", "85000000-0000-4000-8000-000000000004");
    payload.put("intentDigest", codec.digest("maintenance-intent", body));
    payload.put("previousState", body.path("expectedState").textValue());
    payload.put("tenantId", "7");
    payload.put(
        "originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
    payload.put("result", status == null ? "UPDATED" : "NO_EFFECT");
    if (status != null) payload.put("status", status);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    var outer = new TreeMap<String, Object>();
    outer.put("operationId", body.path("operationId").textValue());
    outer.put("accountId", ACCOUNT);
    outer.put("result", status == null ? "UPDATED" : "NO_EFFECT");
    if (status != null) outer.put("status", status);
    outer.put(
        "receipt",
        encoded
            + "."
            + HexFormat.of()
                .formatHex(
                    hmac(
                        (status == null
                                ? "maintenance-operation-receipt"
                                : "maintenance-no-effect-receipt/v1")
                            + "\n"
                            + encoded)));
    return codec.canonical(outer);
  }

  private ObjectNode metadata(boolean self) throws Exception {
    var metadata = json.createObjectNode();
    metadata.put("v", 1);
    metadata.put("realmId", "synthetic-realm");
    metadata.put("operationId", OP);
    metadata.put("command", self ? "account.self-password" : "account.reactivate");
    metadata.put("method", "POST");
    metadata.put(
        "route",
        "oriso-account-maintenance/v1/accounts/" + (self ? "self-password" : "reactivate"));
    metadata.put("accountId", ACCOUNT);
    metadata.put("tenantId", 7L);
    metadata.put("ownerClient", "backend-account-admin");
    metadata.put("ownerSubject", "85000000-0000-4000-8000-000000000004");
    var body = json.readTree(plaintext(self)).path("body");
    metadata.put(
        "originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
    metadata.put("workflowId", OP);
    metadata.put("requestDigest", "a".repeat(64));
    metadata.put("expectedState", "b".repeat(64));
    metadata.put("authorityValidUntil", 32503680000L);
    metadata.put(
        "representation", self ? "FROZEN_PASSWORD_HTTP_V1" : "FROZEN_REACTIVATION_HTTP_V1");
    return metadata;
  }

  private byte[] plaintext(boolean self) throws Exception {
    var origin = new TreeMap<String, Object>();
    origin.put("kind", self ? "SELF" : "HUMAN_ADMIN");
    origin.put("workflowId", OP);
    origin.put("humanSubject", self ? ACCOUNT : HUMAN);
    origin.put("humanIssuer", "https://public-auth.example.test/realms/synthetic");
    origin.put("humanClient", self ? "app" : "admin");
    origin.put("humanAuthority", self ? null : "PROTECTED_ACCOUNT_OPERATIONS_V1");
    var body = new TreeMap<String, Object>();
    body.put("target", Map.of("accountId", ACCOUNT, "tenantId", "7", "origin", origin));
    body.put("operationId", OP);
    body.put("expectedState", "b".repeat(64));
    body.put(
        "change",
        self
            ? Map.of(
                "currentPassword",
                "synthetic-original-password",
                "password",
                "synthetic-chosen-password",
                "temporary",
                false)
            : Map.of(
                "expectedUsername",
                "soft-deleted-asker",
                "expectedEmail",
                "asker@example.test",
                "password",
                "synthetic-chosen-password"));
    if (self) {
      body.put("authorityValidUntil", 32503680000L);
      var local = new TreeMap<String, Object>();
      local.put("kind", "USER");
      local.put("updateDate", null);
      local.put("passwordChangeRequired", null);
      return bytes(json.valueToTree(Map.of("body", body, "localSetup", local)));
    }
    var row = new TreeMap<String, Object>();
    row.put("user_id", ACCOUNT);
    row.put("id_old", null);
    row.put("username", "soft-deleted-asker");
    row.put("email", "asker@example.test");
    row.put("matrix_user_id", null);
    row.put("chat_recovery_mode", "RECOVERY_KEY");
    row.put("chat_recovery_policy_revision", 0L);
    row.put("language_formal", true);
    row.put("delete_date", "2026-10-20T00:00:00Z");
    row.put("deletion_lifecycle_state", "REACTIVATION_IN_PROGRESS");
    row.put("deletion_read_only_until", null);
    row.put("deletion_paused_until", null);
    row.put("deletion_pause_reason", null);
    row.put("deletion_paused_by", null);
    row.put("deletion_pause_created_at", null);
    row.put("reactivation_operation_id", OP);
    row.put("reactivation_operation_started_at", "2026-10-22T00:00:00Z");
    row.put("tenant_id", 7L);
    row.put("create_date", "2025-10-22T00:00:00Z");
    row.put("update_date", "2026-10-22T00:00:00Z");
    row.put("encourage_2fa", true);
    row.put("magic_link_login_enabled", false);
    row.put("language_code", "de");
    row.put("terms_and_conditions_confirmation", null);
    row.put("data_privacy_confirmation", null);
    row.put("notifications_enabled", true);
    row.put("notifications_settings", null);
    row.put("temporary_account", false);
    row.put("avatar_id", null);
    return bytes(json.valueToTree(Map.of("body", body, "originalSql", row)));
  }

  private Set<String> names(JsonNode value) {
    var result = new HashSet<String>();
    value.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private byte[] bytes(JsonNode value) {
    return codec.canonical(value).getBytes(StandardCharsets.UTF_8);
  }

  private byte[] hmac(String input) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] independent(JsonNode metadata, JsonNode envelope) throws Exception {
    var key =
        hmac(
            "oriso/user-maintenance-intent/encryption/v1\n"
                + HexFormat.of()
                    .formatHex(
                        hmac(
                            "oriso/user-maintenance-intent/metadata/v1\n"
                                + codec.canonical(metadata))));
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(
            128, Base64.getUrlDecoder().decode(envelope.path("nonce").textValue())));
    cipher.updateAAD(bytes(metadata));
    return cipher.doFinal(Base64.getUrlDecoder().decode(envelope.path("ciphertext").textValue()));
  }

  private String independentSeal(JsonNode metadata, byte[] plaintext) throws Exception {
    byte[] nonce = HexFormat.of().parseHex("000102030405060708090a0b");
    var key =
        hmac(
            "oriso/user-maintenance-intent/encryption/v1\n"
                + HexFormat.of()
                    .formatHex(
                        hmac(
                            "oriso/user-maintenance-intent/metadata/v1\n"
                                + codec.canonical(metadata))));
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD(bytes(metadata));
    return codec.canonical(
        Map.of(
            "v",
            1,
            "nonce",
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
            "ciphertext",
            Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(plaintext))));
  }
}
