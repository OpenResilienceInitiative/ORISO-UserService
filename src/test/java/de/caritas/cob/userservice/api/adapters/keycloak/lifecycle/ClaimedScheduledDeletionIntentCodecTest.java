package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Independent fixed metadata MAC; these data objects never construct a live deletion frame. */
class ClaimedScheduledDeletionIntentCodecTest {
  private static final byte[] KEY =
      "synthetic-scheduled-deletion-intent-key".getBytes(StandardCharsets.UTF_8);
  private static final String ACCOUNT = "71000000-0000-4000-8000-000000000001";
  private static final String OP = "71000000-0000-4000-8000-000000000002";
  private final ObjectMapper json = new ObjectMapper();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);

  @Test
  void fixedScheduledEnvelopeBindsAllOriginalSqlFieldsAndUnchangedNativeBody() throws Exception {
    ObjectNode envelope = envelope();
    String digest = assertDoesNotThrow(() -> codec.claimedScheduledDeletionIntentDigest(envelope));
    assertEquals(independent(envelope), digest);
    assertEquals(29, envelope.path("originalSql").size());
    for (String field : List.of("username", "email", "matrix_user_id", "create_date")) {
      var changed = envelope.deepCopy();
      ((ObjectNode) changed.path("originalSql"))
          .put(field, field.equals("create_date") ? "2018-01-02T00:00:00Z" : "changed-original");
      assertEquals(independent(changed), codec.claimedScheduledDeletionIntentDigest(changed));
      assertNotEquals(digest, codec.claimedScheduledDeletionIntentDigest(changed));
    }
    var changedBody = envelope.deepCopy();
    ((ObjectNode) changedBody.path("body")).put("expectedState", "b".repeat(64));
    assertNotEquals(digest, codec.claimedScheduledDeletionIntentDigest(changedBody));
    assertEquals(independent(changedBody), codec.claimedScheduledDeletionIntentDigest(changedBody));
  }

  @Test
  void fixedEnvelopeRejectsUnboundFieldsSecretsRawBodiesAndWrongIdentityOrLifecycle() {
    var valid = envelope();
    for (String field : fields(valid)) {
      var missing = valid.deepCopy();
      missing.remove(field);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedScheduledDeletionIntentDigest(missing));
    }
    for (String field : fields(valid.path("originalSql"))) {
      var missing = valid.deepCopy();
      ((ObjectNode) missing.path("originalSql")).remove(field);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedScheduledDeletionIntentDigest(missing),
          field);
    }
    for (String extra : List.of("password", "bearer", "mobile_token", "frozen_intent")) {
      var changed = valid.deepCopy();
      ((ObjectNode) changed.path("originalSql")).put(extra, "synthetic-forbidden");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedScheduledDeletionIntentDigest(changed),
          extra);
    }
    for (String field :
        List.of("user_id", "tenant_id", "deletion_lifecycle_state", "delete_date", "create_date")) {
      var changed = valid.deepCopy();
      ((ObjectNode) changed.path("originalSql")).put(field, "wrong");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedScheduledDeletionIntentDigest(changed),
          field);
    }
    for (String name : List.of("representation", "body", "originalSql")) {
      var changed = valid.deepCopy();
      changed.put(name, "arbitrary");
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedScheduledDeletionIntentDigest(changed),
          name);
    }
    var foreign = valid.deepCopy();
    ((ObjectNode) foreign.path("body").path("target")).put("accountId", OP);
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedScheduledDeletionIntentDigest(foreign));
    var extra = valid.deepCopy();
    extra.put("bindingDigest", "a".repeat(64));
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedScheduledDeletionIntentDigest(extra));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledDeletionIntentDigest(valid.path("body")));
  }

  @Test
  void inactivityBindingPreservesActualActiveUserOrExplicitCapturedAbsenceWithoutCreatingWork()
      throws Exception {
    for (boolean local : List.of(true, false)) {
      var envelope = inactivityEnvelope(local);
      String digest =
          assertDoesNotThrow(() -> codec.claimedInactivityDeletionIntentDigest(envelope));
      assertEquals(independent(envelope, "claimed-inactivity-deletion-intent/v1"), digest);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedScheduledDeletionIntentDigest(envelope));
      if (local) {
        assertEquals(
            "ACTIVE", envelope.path("originalSql").path("deletion_lifecycle_state").textValue());
        assertTrue(envelope.path("originalSql").path("delete_date").isNull());
      } else assertTrue(envelope.path("originalSql").isNull());
    }
  }

  @Test
  void inactivityEnvelopeRejectsForeignBodyRowDigestAndMissingOrAddedOwnershipData()
      throws Exception {
    var valid = inactivityEnvelope(true);
    for (String field : fields(valid)) {
      var missing = valid.deepCopy();
      missing.remove(field);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.claimedInactivityDeletionIntentDigest(missing),
          field);
    }
    var foreign = valid.deepCopy();
    ((ObjectNode) foreign.path("body")).put("operationId", ACCOUNT);
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedInactivityDeletionIntentDigest(foreign));
    var changed = valid.deepCopy();
    ((ObjectNode) changed.path("originalRow")).put("claimedAtMicros", 1769904000123457L);
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedInactivityDeletionIntentDigest(changed));
    var extra = valid.deepCopy();
    extra.put("bindingDigest", "a".repeat(64));
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedInactivityDeletionIntentDigest(extra));
    var badRow = valid.deepCopy();
    badRow.put("rowDigest", "a".repeat(64));
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedInactivityDeletionIntentDigest(badRow));
  }

  @Test
  void preparedScheduledEnvelopeBindsOriginalSqlWithOnlyExplicitNullNativeBody() throws Exception {
    var prepared = envelope();
    prepared.putNull("body");
    String digest = assertDoesNotThrow(() -> codec.claimedScheduledDeletionIntentDigest(prepared));
    assertEquals(independent(prepared), digest);
    assertEquals(29, prepared.path("originalSql").size());
    var changed = prepared.deepCopy();
    ((ObjectNode) changed.path("originalSql")).put("create_date", "2018-01-02T00:00:00Z");
    assertNotEquals(digest, codec.claimedScheduledDeletionIntentDigest(changed));
    var missing = prepared.deepCopy();
    missing.remove("body");
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedScheduledDeletionIntentDigest(missing));
    var invalid = prepared.deepCopy();
    invalid.putObject("body");
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedScheduledDeletionIntentDigest(invalid));
    var foreign = prepared.deepCopy();
    foreign.put("representation", "CLAIMED_INACTIVITY_DELETE_V1");
    assertThrows(
        IllegalArgumentException.class, () -> codec.claimedScheduledDeletionIntentDigest(foreign));
    var noOriginal = prepared.deepCopy();
    noOriginal.putNull("originalSql");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledDeletionIntentDigest(noOriginal));
  }

  private ObjectNode inactivityEnvelope(boolean local) throws Exception {
    var result = envelope();
    result.put("representation", "CLAIMED_INACTIVITY_DELETE_V1");
    if (local) {
      var sql = (ObjectNode) result.path("originalSql");
      sql.put("deletion_lifecycle_state", "ACTIVE");
      sql.putNull("delete_date");
      sql.putNull("deletion_read_only_until");
    } else result.putNull("originalSql");
    var row = result.putObject("originalRow");
    row.put("identityId", ACCOUNT);
    row.put("tenantId", "7");
    row.put("assignedMonths", 24);
    row.put("revision", 0);
    row.put("lastActivityMicros", 1672531200123456L);
    row.put("dueAtMicros", 1735689600123456L);
    row.put("status", "DELETING");
    row.put("attempts", 0);
    row.putNull("lastError");
    row.put("workId", OP);
    row.put("action", "DELETE");
    row.putObject("initiator").put("kind", "SCHEDULED");
    row.putNull("suspensionOperationId");
    row.put("claimedAtMicros", 1769904000123456L);
    result.put("rowDigest", independent(row, "claimed-inactivity-deletion-row/v1"));
    return result;
  }

  private ObjectNode envelope() {
    var row = new TreeMap<String, Object>();
    row.put("user_id", ACCOUNT);
    row.put("id_old", null);
    row.put("username", "existing-asker");
    row.put("email", "asker@example.test");
    row.put("matrix_user_id", "@owned:matrix.example.test");
    row.put("chat_recovery_mode", "RECOVERY_KEY");
    row.put("chat_recovery_policy_revision", 0L);
    row.put("language_formal", true);
    row.put("delete_date", "2020-01-01T00:00:00Z");
    row.put("deletion_lifecycle_state", "HARD_DELETE_IN_PROGRESS");
    row.put("deletion_read_only_until", "2020-01-03T00:00:00Z");
    for (String field :
        List.of(
            "deletion_paused_until",
            "deletion_pause_reason",
            "deletion_paused_by",
            "deletion_pause_created_at",
            "reactivation_operation_id",
            "reactivation_operation_started_at",
            "update_date",
            "terms_and_conditions_confirmation",
            "data_privacy_confirmation",
            "notifications_settings",
            "avatar_id")) row.put(field, null);
    row.put("tenant_id", 7L);
    row.put("create_date", "2019-01-01T00:00:00Z");
    row.put("encourage_2fa", true);
    row.put("magic_link_login_enabled", false);
    row.put("language_code", "de");
    row.put("notifications_enabled", true);
    row.put("temporary_account", false);
    var origin = new TreeMap<String, Object>();
    origin.put("kind", "DELETION");
    origin.put("workflowId", OP);
    for (String field : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.put(field, null);
    return json.valueToTree(
        Map.of(
            "representation",
            "CLAIMED_SCHEDULED_ASKER_DELETE_V1",
            "originalSql",
            row,
            "body",
            Map.of(
                "target",
                Map.of("accountId", ACCOUNT, "tenantId", "7", "origin", origin),
                "operationId",
                OP,
                "expectedState",
                "a".repeat(64),
                "change",
                Map.of())));
  }

  private Set<String> fields(JsonNode node) {
    var result = new HashSet<String>();
    node.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private String independent(JsonNode node) throws Exception {
    return independent(node, "claimed-scheduled-deletion-intent/v1");
  }

  private String independent(JsonNode node, String domain) throws Exception {
    String canonical =
        json.writer()
            .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsString(json.convertValue(node, Object.class));
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((domain + "\n" + canonical).getBytes(StandardCharsets.UTF_8)));
  }
}
