package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ClaimedConsultantDeletionCodecTest {
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String OPERATION = "22222222-2222-4222-8222-222222222222";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
  private static final List<String> COLUMNS =
      List.of(
          "managed_paused",
          "consultant_id",
          "matrix_user_id",
          "chat_recovery_mode",
          "chat_recovery_policy_revision",
          "username",
          "first_name",
          "last_name",
          "email",
          "is_absent",
          "is_team_consultant",
          "is_supervisor",
          "assigned_supervisor_id",
          "display_name",
          "internal_display_name",
          "salutation",
          "position",
          "title",
          "admin_remarks",
          "avatar_id",
          "avatar_kind",
          "public_slug",
          "pending_public_slug",
          "public_slug_status",
          "public_slug_reviewed_at",
          "absence_message",
          "language_formal",
          "id_old",
          "create_date",
          "update_date",
          "delete_date",
          "deletion_lifecycle_state",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_reason",
          "deletion_paused_by",
          "deletion_pause_created_at",
          "encourage_2fa",
          "two_factor_required",
          "topic_permission",
          "password_change_required",
          "live_chat_via_sidebar",
          "magic_link_login_enabled",
          "notify_enquiries_repeating",
          "notify_new_chat_message_from_advice_seeker",
          "tenant_id",
          "status",
          "walk_through_enabled",
          "language_code",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation",
          "notifications_enabled",
          "notifications_settings");
  private static final Set<String> FLAGS =
      Set.of(
          "managed_paused",
          "is_absent",
          "is_team_consultant",
          "is_supervisor",
          "language_formal",
          "encourage_2fa",
          "two_factor_required",
          "password_change_required",
          "live_chat_via_sidebar",
          "magic_link_login_enabled",
          "notify_enquiries_repeating",
          "notify_new_chat_message_from_advice_seeker",
          "walk_through_enabled",
          "notifications_enabled");
  private static final Set<String> TIMES =
      Set.of(
          "public_slug_reviewed_at",
          "create_date",
          "update_date",
          "delete_date",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_created_at",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation");
  private static final Set<String> LONGS =
      Set.of("id_old", "chat_recovery_policy_revision", "tenant_id");

  @Test
  void everyTypedScalarIsBoundByTheFixedRowMac() {
    var row = row();
    String digest = codec.claimedScheduledConsultantDeletionRowDigest(row);
    assertEquals(expected("claimed-scheduled-consultant-deletion-row/v1", row), digest);
    for (String name : COLUMNS) {
      var changed = row.deepCopy();
      if (name.equals("deletion_lifecycle_state")) {
        changed.put(name, "ACTIVE");
        assertThrows(
            IllegalArgumentException.class,
            () -> codec.claimedScheduledConsultantDeletionRowDigest(changed));
        continue;
      }
      if (name.equals("consultant_id")) changed.put(name, OPERATION);
      else if (FLAGS.contains(name)) changed.put(name, !row.path(name).asBoolean());
      else if (LONGS.contains(name)) changed.put(name, row.path(name).asLong() + 1);
      else if (TIMES.contains(name))
        changed.put(name, Instant.parse("2026-10-21T00:00:00Z").plusNanos(1000).toString());
      else if (!row.path(name).isNull()) changed.putNull(name);
      else
        changed.put(
            name,
            switch (name) {
              case "avatar_kind" -> "ICON";
              case "public_slug_status" -> "APPROVED";
              case "topic_permission" -> "CREATE";
              case "status" -> "IN_PROGRESS";
              case "language_code" -> "en";
              default -> "different exact scalar";
            });
      if (name.equals("username")) changed.put(name, "changed encoded username");
      assertNotEquals(digest, codec.claimedScheduledConsultantDeletionRowDigest(changed), name);
    }
  }

  @Test
  void nullAndPrimitiveTypesCannotCollapseIntoOneRow() {
    var row = row();
    var nullable = row.deepCopy();
    nullable.putNull("encourage_2fa");
    assertNotEquals(
        codec.claimedScheduledConsultantDeletionRowDigest(row),
        codec.claimedScheduledConsultantDeletionRowDigest(nullable));
    var wrong = row.deepCopy();
    wrong.put("encourage_2fa", "false");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionRowDigest(wrong));
    var wrongTenant = row.deepCopy();
    wrongTenant.put("tenant_id", "7");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionRowDigest(wrongTenant));
    var extra = row.deepCopy();
    extra.put("callerVersion", 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionRowDigest(extra));
    var missing = row.deepCopy();
    missing.remove("admin_remarks");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionRowDigest(missing));
  }

  @Test
  void fixedEnvelopeBindsTheOriginalCoreAndOnlyTheDeleteBody() {
    var value = envelope();
    String digest = codec.claimedScheduledConsultantDeletionIntentDigest(value);
    assertEquals(
        digest,
        codec.claimedScheduledConsultantDeletionIntentDigest(
            codec.read(codec.canonical(value), com.fasterxml.jackson.databind.JsonNode.class)));
    assertEquals(expected("claimed-scheduled-consultant-deletion-intent/v1", value), digest);
    var other = value.deepCopy();
    ((ObjectNode) other.path("originalSql")).put("matrixUserId", "@other:fixture.invalid");
    assertNotEquals(digest, codec.claimedScheduledConsultantDeletionIntentDigest(other));
    var bad = value.deepCopy();
    ((ObjectNode) bad.path("originalSql")).put("v", 4294967297L);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionIntentDigest(bad));
    var foreign = value.deepCopy();
    ((ObjectNode) foreign.path("body").path("target")).put("tenantId", "8");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionIntentDigest(foreign));
    var grants = value.deepCopy();
    ((ObjectNode) grants.path("body").path("change")).put("enabled", false);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionIntentDigest(grants));
    var human = value.deepCopy();
    ((ObjectNode) human.path("body").path("target").path("origin")).put("humanSubject", ACCOUNT);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.claimedScheduledConsultantDeletionIntentDigest(human));
  }

  @Test
  void fullActualLobUsesOnlyTheNamedDataMacAndGenericReadStaysBounded() {
    var row = row();
    row.put("absence_message", "🦉\n".repeat(30000));
    String raw = codec.canonical(row);
    assertTrue(raw.getBytes(StandardCharsets.UTF_8).length > 65536);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
    assertEquals(
        expected("claimed-scheduled-consultant-deletion-row/v1", row),
        assertDoesNotThrow(() -> codec.claimedScheduledConsultantDeletionRowDigest(row)));
    var changed = row.deepCopy();
    changed.put("absence_message", row.path("absence_message").textValue() + "last exact byte");
    assertNotEquals(
        codec.claimedScheduledConsultantDeletionRowDigest(row),
        codec.claimedScheduledConsultantDeletionRowDigest(changed));
  }

  private ObjectNode row() {
    var row = JSON.createObjectNode();
    COLUMNS.forEach(row::putNull);
    FLAGS.forEach(name -> row.put(name, false));
    row.put("consultant_id", ACCOUNT);
    row.put("username", "encoded original username");
    row.put("tenant_id", 7L);
    row.put("create_date", "2026-10-21T00:00:00Z");
    row.put("delete_date", "2026-10-21T00:00:00Z");
    row.put("deletion_lifecycle_state", "READ_ONLY_SAFEGUARD");
    return row;
  }

  private ObjectNode envelope() {
    var original = new TreeMap<String, Object>();
    original.put("v", 1);
    original.put("consultantId", ACCOUNT);
    original.put("tenantId", 7L);
    original.put("createDate", "2026-10-21T00:00:00Z");
    original.put("deleteDate", "2026-10-21T00:00:00Z");
    original.put("username", "encoded original username");
    original.put("matrixUserId", null);
    original.put("rowDigest", codec.claimedScheduledConsultantDeletionRowDigest(row()));
    var origin = new TreeMap<String, Object>();
    origin.put("kind", "DELETION");
    origin.put("workflowId", OPERATION);
    for (String name : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.put(name, null);
    return JSON.valueToTree(
        Map.of(
            "representation",
            "CLAIMED_SCHEDULED_CONSULTANT_DELETE_V1",
            "originalSql",
            original,
            "body",
            Map.of(
                "target",
                Map.of("accountId", ACCOUNT, "tenantId", "7", "origin", origin),
                "operationId",
                OPERATION,
                "expectedState",
                "a".repeat(64),
                "change",
                Map.of())));
  }

  private String expected(String domain, Object value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(
                  (domain + "\n" + codec.canonical(value)).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
