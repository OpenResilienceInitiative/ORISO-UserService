package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact User29/Consultant53 data, never an acknowledgement or native grant. */
final class PreDeletionSqlSnapshot {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> USER =
      Set.of(
          "user_id",
          "id_old",
          "username",
          "email",
          "matrix_user_id",
          "chat_recovery_mode",
          "chat_recovery_policy_revision",
          "language_formal",
          "delete_date",
          "deletion_lifecycle_state",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_reason",
          "deletion_paused_by",
          "deletion_pause_created_at",
          "reactivation_operation_id",
          "reactivation_operation_started_at",
          "tenant_id",
          "create_date",
          "update_date",
          "encourage_2fa",
          "magic_link_login_enabled",
          "language_code",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation",
          "notifications_enabled",
          "notifications_settings",
          "temporary_account",
          "avatar_id");
  private static final Set<String> CONSULTANT =
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
  private static final Set<String> TIMES =
      Set.of(
          "create_date",
          "data_privacy_confirmation",
          "delete_date",
          "deletion_pause_created_at",
          "deletion_paused_until",
          "deletion_read_only_until",
          "public_slug_reviewed_at",
          "reactivation_operation_started_at",
          "terms_and_conditions_confirmation",
          "update_date");
  private static final Set<String> NUMBERS =
      Set.of("chat_recovery_policy_revision", "id_old", "tenant_id");
  private static final Set<String> FLAGS =
      Set.of(
          "encourage_2fa",
          "is_absent",
          "is_supervisor",
          "is_team_consultant",
          "language_formal",
          "live_chat_via_sidebar",
          "magic_link_login_enabled",
          "managed_paused",
          "notifications_enabled",
          "notify_enquiries_repeating",
          "notify_new_chat_message_from_advice_seeker",
          "password_change_required",
          "temporary_account",
          "two_factor_required",
          "walk_through_enabled");

  record Snapshot(JsonNode full, JsonNode core) {}

  static Snapshot capture(
      JdbcTemplate jdbc,
      AccountLifecycleCodec codec,
      boolean consultant,
      String account,
      boolean lock) {
    String table = consultant ? "consultant" : "user",
        id = consultant ? "consultant_id" : "user_id";
    var columns = consultant ? CONSULTANT : USER;
    var rows =
        jdbc.query(
            "select * from " + table + " where " + id + "=?" + (lock ? " for update" : ""),
            (rs, index) -> {
              var observed = new HashSet<String>();
              for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                observed.add(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT));
              if (!observed.equals(columns)) throw invalid();
              var row = new TreeMap<String, Object>();
              for (String name : columns) {
                Object value;
                if (TIMES.contains(name)) {
                  LocalDateTime time = rs.getObject(name, LocalDateTime.class);
                  value = time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
                } else if (NUMBERS.contains(name)) {
                  long number = rs.getLong(name);
                  value = rs.wasNull() ? null : number;
                } else if (FLAGS.contains(name)) {
                  boolean flag = rs.getBoolean(name);
                  value = rs.wasNull() ? null : flag;
                } else value = rs.getString(name);
                row.put(name, value);
              }
              JsonNode full = JSON.valueToTree(row);
              String created = full.path("create_date").textValue(),
                  username = full.path("username").textValue();
              if (created == null
                  || java.time.Instant.parse(created).getEpochSecond() <= 0
                  || username == null
                  || username.isBlank()
                  || !account.equals(full.path(id).textValue())) throw invalid();
              var core = new TreeMap<String, Object>();
              core.put("v", 1);
              core.put("kind", consultant ? "CONSULTANT" : "USER");
              core.put("accountId", account);
              core.put("tenantId", row.get("tenant_id"));
              core.put("createDate", created);
              core.put("username", username);
              core.put("matrixUserId", row.get("matrix_user_id"));
              // Direct server-owned full53/LOB MAC. The bounded parser sees only the fixed
              // eight-field core.
              core.put(
                  "rowDigest",
                  codec.digest(
                      "public-pre-deletion-row/v1",
                      Map.of("kind", consultant ? "CONSULTANT" : "USER", "row", full)));
              return new Snapshot(full, codec.read(codec.canonical(core), JsonNode.class));
            },
            account);
    if (rows.size() != 1) throw invalid();
    return rows.getFirst();
  }

  static JsonNode requireCore(JsonNode core) {
    var fields = new HashSet<String>();
    core.fieldNames().forEachRemaining(fields::add);
    if (!core.isObject()
        || !fields.equals(
            Set.of(
                "v",
                "kind",
                "accountId",
                "tenantId",
                "createDate",
                "username",
                "matrixUserId",
                "rowDigest"))
        || !core.path("v").isIntegralNumber()
        || core.path("v").intValue() != 1
        || !Set.of("USER", "CONSULTANT").contains(core.path("kind").asText())
        || !core.path("accountId").isTextual()
        || !UUID.fromString(core.path("accountId").asText())
            .toString()
            .equals(core.path("accountId").asText())
        || !core.path("createDate").isTextual()
        || !core.path("username").isTextual()
        || core.path("username").asText().isBlank()
        || !core.path("rowDigest").isTextual()
        || !core.path("rowDigest").asText().matches("[0-9a-f]{64}")
        || !core.path("tenantId").isNull()
            && (!core.path("tenantId").isIntegralNumber()
                || !core.path("tenantId").canConvertToLong()
                || core.path("tenantId").longValue() < 0)
        || !core.path("matrixUserId").isNull() && !core.path("matrixUserId").isTextual())
      throw invalid();
    java.time.Instant.parse(core.path("createDate").asText());
    return core;
  }

  static boolean sameBirth(JsonNode original, JsonNode current) {
    requireCore(original);
    requireCore(current);
    for (String key :
        List.of("v", "kind", "accountId", "tenantId", "createDate", "username", "matrixUserId"))
      if (!original.path(key).equals(current.path(key))) return false;
    return true;
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Original pre-deletion SQL ownership unavailable");
  }

  private PreDeletionSqlSnapshot() {}
}
