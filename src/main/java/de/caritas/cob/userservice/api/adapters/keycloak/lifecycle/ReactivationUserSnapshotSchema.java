package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Closed nonsecret data only. The actual saga independently proves and rechecks SQL ownership. */
final class ReactivationUserSnapshotSchema {
  private static final Set<String> FIELDS =
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

  private ReactivationUserSnapshotSchema() {}

  static JsonNode capture(
      org.springframework.jdbc.core.JdbcTemplate jdbc,
      AccountLifecycleCodec codec,
      String account,
      boolean lock) {
    var rows =
        jdbc.query(
            "select "
                + String.join(",", new TreeSet<>(FIELDS))
                + " from user where user_id=?"
                + (lock ? " for update" : ""),
            (rs, index) -> {
              var values = new TreeMap<String, Object>();
              for (String name : FIELDS) {
                Object value;
                if (Set.of(
                        "create_date",
                        "update_date",
                        "delete_date",
                        "deletion_read_only_until",
                        "deletion_paused_until",
                        "deletion_pause_created_at",
                        "reactivation_operation_started_at",
                        "terms_and_conditions_confirmation",
                        "data_privacy_confirmation")
                    .contains(name)) {
                  var time = rs.getObject(name, java.time.LocalDateTime.class);
                  value = time == null ? null : time.toInstant(java.time.ZoneOffset.UTC).toString();
                } else if (Set.of("id_old", "chat_recovery_policy_revision", "tenant_id")
                    .contains(name)) {
                  long number = rs.getLong(name);
                  value = rs.wasNull() ? null : number;
                } else if (Set.of(
                        "language_formal",
                        "encourage_2fa",
                        "magic_link_login_enabled",
                        "notifications_enabled",
                        "temporary_account")
                    .contains(name)) {
                  boolean flag = rs.getBoolean(name);
                  value = rs.wasNull() ? null : flag;
                } else value = rs.getString(name);
                values.put(name, value);
              }
              return codec.read(codec.canonical(values), JsonNode.class);
            },
            account);
    if (rows.size() != 1) throw invalid();
    return rows.getFirst();
  }

  static JsonNode from(
      de.caritas.cob.userservice.api.model.User user, AccountLifecycleCodec codec) {
    var row = new TreeMap<String, Object>();
    row.put("user_id", user.getUserId());
    row.put("id_old", user.getOldId());
    row.put("username", user.getUsername());
    row.put("email", user.getEmail());
    row.put("matrix_user_id", user.getMatrixUserId());
    row.put("chat_recovery_mode", user.getChatRecoveryMode());
    row.put("chat_recovery_policy_revision", user.getChatRecoveryPolicyRevision());
    row.put("language_formal", user.isLanguageFormal());
    row.put("delete_date", time(user.getDeleteDate()));
    row.put(
        "deletion_lifecycle_state",
        user.getDeletionLifecycleState() == null ? null : user.getDeletionLifecycleState().name());
    row.put("deletion_read_only_until", time(user.getDeletionReadOnlyUntil()));
    row.put("deletion_paused_until", time(user.getDeletionPausedUntil()));
    row.put("deletion_pause_reason", user.getDeletionPauseReason());
    row.put("deletion_paused_by", user.getDeletionPausedBy());
    row.put("deletion_pause_created_at", time(user.getDeletionPauseCreatedAt()));
    row.put("reactivation_operation_id", user.getReactivationOperationId());
    row.put("reactivation_operation_started_at", time(user.getReactivationOperationStartedAt()));
    row.put("tenant_id", user.getTenantId());
    row.put("create_date", time(user.getCreateDate()));
    row.put("update_date", time(user.getUpdateDate()));
    row.put("encourage_2fa", user.getEncourage2fa());
    row.put("magic_link_login_enabled", user.getMagicLinkLoginEnabled());
    row.put(
        "language_code", user.getLanguageCode() == null ? null : user.getLanguageCode().toString());
    row.put("terms_and_conditions_confirmation", time(user.getTermsAndConditionsConfirmation()));
    row.put("data_privacy_confirmation", time(user.getDataPrivacyConfirmation()));
    row.put("notifications_enabled", user.isNotificationsEnabled());
    row.put("notifications_settings", user.getNotificationsSettings());
    row.put("temporary_account", user.isTemporaryAccount());
    row.put("avatar_id", user.getAvatarId());
    if (!FIELDS.equals(row.keySet())) throw invalid();
    return codec.read(codec.canonical(row), JsonNode.class);
  }

  private static String time(java.time.LocalDateTime value) {
    return value == null ? null : value.toInstant(java.time.ZoneOffset.UTC).toString();
  }

  static void require(JsonNode row, String account, Long tenant, String operation) {
    if (!row.isObject() || row.size() != FIELDS.size()) throw invalid();
    var actual = new HashSet<String>();
    row.fieldNames().forEachRemaining(actual::add);
    if (!FIELDS.equals(actual)
        || !account.equals(uuid(row, "user_id"))
        || !operation.equals(uuid(row, "reactivation_operation_id"))
        || tenant == null
        || tenant < 0
        || !row.path("tenant_id").isIntegralNumber()
        || !row.path("tenant_id").canConvertToLong()
        || tenant.longValue() != row.path("tenant_id").longValue()
        || !"REACTIVATION_IN_PROGRESS".equals(text(row, "deletion_lifecycle_state", 32, false)))
      throw invalid();
    nullableLong(row, "id_old", false);
    nullableLong(row, "chat_recovery_policy_revision", true);
    text(row, "username", 255, false);
    text(row, "email", 255, false);
    nullableText(row, "matrix_user_id", 255);
    nullableText(row, "deletion_pause_reason", 512);
    nullableText(row, "deletion_paused_by", 64);
    nullableText(row, "notifications_settings", 4000);
    nullableText(row, "avatar_id", 40);
    if (!row.get("chat_recovery_mode").isNull()
        && !Set.of("LOGIN_PASSWORD", "RECOVERY_KEY")
            .contains(text(row, "chat_recovery_mode", 32, false))) throw invalid();
    if (!text(row, "language_code", 2, false).matches("[a-z]{2}")) throw invalid();
    for (String field : List.of("language_formal", "notifications_enabled", "temporary_account"))
      if (!row.path(field).isBoolean()) throw invalid();
    for (String field : List.of("encourage_2fa", "magic_link_login_enabled"))
      if (!row.path(field).isNull() && !row.path(field).isBoolean()) throw invalid();
    for (String field : List.of("create_date", "delete_date", "reactivation_operation_started_at"))
      timestamp(row, field, false);
    for (String field :
        List.of(
            "update_date",
            "deletion_read_only_until",
            "deletion_paused_until",
            "deletion_pause_created_at",
            "terms_and_conditions_confirmation",
            "data_privacy_confirmation")) timestamp(row, field, true);
  }

  private static void timestamp(JsonNode row, String field, boolean nullable) {
    if (row.path(field).isNull() && nullable) return;
    String raw = text(row, field, 64, false);
    try {
      var time = Instant.parse(raw);
      if (time.getEpochSecond() <= 0 || time.getNano() % 1000 != 0 || !time.toString().equals(raw))
        throw invalid();
    } catch (java.time.DateTimeException invalid) {
      throw invalid();
    }
  }

  private static void nullableLong(JsonNode row, String field, boolean nonnegative) {
    var value = row.path(field);
    if (value.isNull()) return;
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || nonnegative && value.longValue() < 0) throw invalid();
  }

  private static void nullableText(JsonNode row, String field, int max) {
    if (!row.path(field).isNull()) text(row, field, max, true);
  }

  private static String uuid(JsonNode row, String field) {
    String id = text(row, field, 36, false);
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw invalid();
      return id;
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
  }

  private static String text(JsonNode row, String field, int max, boolean empty) {
    var value = row.path(field);
    if (!value.isTextual()
        || value.textValue().length() > max
        || !empty && value.textValue().isBlank()
        || value.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return value.textValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid private reactivation User snapshot");
  }
}
