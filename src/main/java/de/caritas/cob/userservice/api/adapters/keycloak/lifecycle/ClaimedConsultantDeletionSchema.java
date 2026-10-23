package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Fixed Consultant SQL data validation only; no JSON can issue a private continuation. */
final class ClaimedConsultantDeletionSchema {
  private static final Set<String> COLUMNS =
      Set.of(
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
      Set.of("chat_recovery_policy_revision", "id_old", "tenant_id");

  private ClaimedConsultantDeletionSchema() {}

  static JsonNode row(JsonNode value) {
    keys(value, COLUMNS);
    for (String name : COLUMNS) {
      JsonNode item = value.path(name);
      if (FLAGS.contains(name)) {
        if (!item.isNull() && !item.isBoolean()) throw invalid();
      } else if (TIMES.contains(name)) {
        if (!item.isNull()) timestamp(item);
      } else if (LONGS.contains(name)) {
        if (!item.isNull() && (!item.isIntegralNumber() || !item.canConvertToLong()))
          throw invalid();
      } else if (!item.isNull() && !item.isTextual()) throw invalid();
    }
    uuid(value.path("consultant_id"));
    text(value.path("username"), 255, false);
    timestamp(value.path("create_date"));
    timestamp(value.path("delete_date"));
    if (!"READ_ONLY_SAFEGUARD".equals(value.path("deletion_lifecycle_state").textValue()))
      throw invalid();
    enumValue(
        value.path("avatar_kind"), de.caritas.cob.userservice.api.model.ConsultantAvatarKind.class);
    enumValue(
        value.path("public_slug_status"),
        de.caritas.cob.userservice.api.model.PublicSlugStatus.class);
    enumValue(
        value.path("topic_permission"), de.caritas.cob.userservice.api.model.TopicPermission.class);
    enumValue(value.path("status"), de.caritas.cob.userservice.api.model.ConsultantStatus.class);
    enumValue(value.path("language_code"), com.neovisionaries.i18n.LanguageCode.class);
    tenant(value.path("tenant_id"));
    return value;
  }

  static JsonNode envelope(JsonNode value) {
    keys(value, Set.of("representation", "originalSql", "body"));
    if (!"CLAIMED_SCHEDULED_CONSULTANT_DELETE_V1".equals(value.path("representation").textValue()))
      throw invalid();
    var original = value.path("originalSql");
    keys(
        original,
        Set.of(
            "v",
            "consultantId",
            "tenantId",
            "createDate",
            "deleteDate",
            "username",
            "matrixUserId",
            "rowDigest"));
    if (!original.path("v").isIntegralNumber()
        || !original.path("v").canConvertToLong()
        || original.path("v").longValue() != 1) throw invalid();
    String account = uuid(original.path("consultantId"));
    tenant(original.path("tenantId"));
    timestamp(original.path("createDate"));
    timestamp(original.path("deleteDate"));
    text(original.path("username"), 255, false);
    if (!original.path("matrixUserId").isNull()) text(original.path("matrixUserId"), 255, true);
    if (!text(original.path("rowDigest"), 64, false).matches("[0-9a-f]{64}")) throw invalid();
    var body = value.path("body");
    keys(body, Set.of("target", "operationId", "expectedState", "change"));
    String operation = uuid(body.path("operationId"));
    if (!text(body.path("expectedState"), 64, false).matches("[0-9a-f]{64}")
        || !body.path("change").isObject()
        || !body.path("change").isEmpty()) throw invalid();
    var target = body.path("target");
    keys(target, Set.of("accountId", "tenantId", "origin"));
    if (!account.equals(uuid(target.path("accountId")))) throw invalid();
    var localTenant = original.path("tenantId");
    var nativeTenant = target.path("tenantId");
    if (localTenant.isNull()
        ? !nativeTenant.isNull()
        : !nativeTenant.isTextual()
            || !Long.toString(localTenant.longValue()).equals(nativeTenant.textValue()))
      throw invalid();
    var origin = target.path("origin");
    keys(
        origin,
        Set.of(
            "kind", "workflowId", "humanSubject", "humanIssuer", "humanClient", "humanAuthority"));
    if (!"DELETION".equals(origin.path("kind").textValue())
        || !operation.equals(uuid(origin.path("workflowId")))) throw invalid();
    for (String name : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      if (!origin.path(name).isNull()) throw invalid();
    return value;
  }

  private static <E extends Enum<E>> void enumValue(JsonNode value, Class<E> kind) {
    if (value.isNull()) return;
    try {
      Enum.valueOf(kind, value.textValue());
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static void tenant(JsonNode value) {
    if (!value.isNull()
        && (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0))
      throw invalid();
  }

  private static void timestamp(JsonNode value) {
    String text = text(value, 64, false);
    try {
      var instant = Instant.parse(text);
      if (instant.getEpochSecond() <= 0
          || instant.getNano() % 1000 != 0
          || !instant.toString().equals(text)) throw invalid();
    } catch (java.time.DateTimeException malformed) {
      throw invalid();
    }
  }

  private static String uuid(JsonNode value) {
    String text = text(value, 36, false);
    try {
      if (!UUID.fromString(text).toString().equals(text)) throw invalid();
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
    return text;
  }

  private static String text(JsonNode value, int max, boolean empty) {
    if (!value.isTextual()
        || value.textValue().length() > max
        || !empty && value.textValue().isBlank()) throw invalid();
    return value.textValue();
  }

  private static void keys(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject() || value.size() != expected.size()) throw invalid();
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid owned Consultant deletion metadata");
  }
}
