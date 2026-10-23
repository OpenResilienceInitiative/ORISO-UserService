package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.model.User;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** The fixed nonsecret User29 projection. This data helper cannot issue deletion authority. */
final class AskerDeletionSqlSnapshot {
  private static final List<String> FIELDS =
      List.of(
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
  private static final Set<String> TIMES =
      Set.of(
          "delete_date",
          "deletion_read_only_until",
          "deletion_paused_until",
          "deletion_pause_created_at",
          "reactivation_operation_started_at",
          "create_date",
          "update_date",
          "terms_and_conditions_confirmation",
          "data_privacy_confirmation");
  private static final Set<String> NUMBERS =
      Set.of("id_old", "chat_recovery_policy_revision", "tenant_id");
  private static final Set<String> FLAGS =
      Set.of(
          "language_formal",
          "encourage_2fa",
          "magic_link_login_enabled",
          "notifications_enabled",
          "temporary_account");

  private AskerDeletionSqlSnapshot() {}

  static JsonNode capture(
      JdbcTemplate jdbc, AccountLifecycleCodec codec, String account, boolean lock) {
    var rows =
        jdbc.query(
            "select "
                + String.join(",", FIELDS)
                + " from user where user_id=?"
                + (lock ? " for update" : ""),
            (rs, index) -> {
              var row = new TreeMap<String, Object>();
              for (String name : FIELDS) {
                Object value;
                if (TIMES.contains(name)) value = time(rs.getObject(name, LocalDateTime.class));
                else if (NUMBERS.contains(name)) {
                  long number = rs.getLong(name);
                  value = rs.wasNull() ? null : number;
                } else if (FLAGS.contains(name)) {
                  boolean flag = rs.getBoolean(name);
                  value = rs.wasNull() ? null : flag;
                } else value = rs.getString(name);
                row.put(name, value);
              }
              return codec.read(codec.canonical(row), JsonNode.class);
            },
            account);
    if (rows.size() > 1) throw new IllegalStateException("Owned deletion snapshot unavailable");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  static JsonNode from(User user, AccountLifecycleCodec codec) {
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
    row.put("language_code", user.getLanguageCode() == null ? null : user.getLanguageCode().name());
    row.put("terms_and_conditions_confirmation", time(user.getTermsAndConditionsConfirmation()));
    row.put("data_privacy_confirmation", time(user.getDataPrivacyConfirmation()));
    row.put("notifications_enabled", user.isNotificationsEnabled());
    row.put("notifications_settings", user.getNotificationsSettings());
    row.put("temporary_account", user.isTemporaryAccount());
    row.put("avatar_id", user.getAvatarId());
    return codec.read(codec.canonical(row), JsonNode.class);
  }

  private static String time(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
  }
}
