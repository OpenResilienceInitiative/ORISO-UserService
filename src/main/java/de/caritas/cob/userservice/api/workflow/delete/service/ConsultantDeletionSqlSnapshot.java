package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.model.Consultant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed complete scalar SQL/JPA projection, never a source of native or live-frame authority. */
final class ConsultantDeletionSqlSnapshot {
  private static final ObjectMapper JSON = new ObjectMapper();
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

  private ConsultantDeletionSqlSnapshot() {}

  static JsonNode capture(
      JdbcTemplate jdbc, AccountLifecycleCodec codec, String account, boolean lock) {
    var rows =
        jdbc.query(
            "select "
                + String.join(",", COLUMNS)
                + " from consultant where consultant_id=?"
                + (lock ? " for update" : ""),
            (rs, index) -> original(sql(rs), codec),
            account);
    if (rows.size() > 1) throw invalid();
    return rows.isEmpty() ? null : rows.get(0);
  }

  static JsonNode from(Consultant consultant, AccountLifecycleCodec codec) {
    if (consultant == null) throw invalid();
    Map<String, Object> row = new TreeMap<>();
    row.put("managed_paused", consultant.isManagedPaused());
    row.put("consultant_id", consultant.getId());
    row.put("matrix_user_id", consultant.getMatrixUserId());
    row.put("chat_recovery_mode", consultant.getChatRecoveryMode());
    row.put("chat_recovery_policy_revision", consultant.getChatRecoveryPolicyRevision());
    row.put("username", consultant.getUsername());
    row.put("first_name", consultant.getFirstName());
    row.put("last_name", consultant.getLastName());
    row.put("email", consultant.getEmail());
    row.put("is_absent", consultant.isAbsent());
    row.put("is_team_consultant", consultant.isTeamConsultant());
    row.put("is_supervisor", consultant.isSupervisor());
    row.put("assigned_supervisor_id", consultant.getAssignedSupervisorId());
    row.put("display_name", consultant.getDisplayName());
    row.put("internal_display_name", consultant.getInternalDisplayName());
    row.put("salutation", consultant.getSalutation());
    row.put("position", consultant.getPosition());
    row.put("title", consultant.getTitle());
    row.put("admin_remarks", consultant.getAdminRemarks());
    row.put("avatar_id", consultant.getAvatarId());
    row.put("avatar_kind", name(consultant.getAvatarKind()));
    row.put("public_slug", consultant.getPublicSlug());
    row.put("pending_public_slug", consultant.getPendingPublicSlug());
    row.put("public_slug_status", name(consultant.getPublicSlugStatus()));
    row.put("public_slug_reviewed_at", time(consultant.getPublicSlugReviewedAt()));
    row.put("absence_message", consultant.getAbsenceMessage());
    row.put("language_formal", consultant.isLanguageFormal());
    row.put("id_old", consultant.getIdOld());
    row.put("create_date", time(consultant.getCreateDate()));
    row.put("update_date", time(consultant.getUpdateDate()));
    row.put("delete_date", time(consultant.getDeleteDate()));
    row.put("deletion_lifecycle_state", name(consultant.getDeletionLifecycleState()));
    row.put("deletion_read_only_until", time(consultant.getDeletionReadOnlyUntil()));
    row.put("deletion_paused_until", time(consultant.getDeletionPausedUntil()));
    row.put("deletion_pause_reason", consultant.getDeletionPauseReason());
    row.put("deletion_paused_by", consultant.getDeletionPausedBy());
    row.put("deletion_pause_created_at", time(consultant.getDeletionPauseCreatedAt()));
    row.put("encourage_2fa", consultant.getEncourage2fa());
    row.put("two_factor_required", consultant.getTwoFactorRequired());
    row.put("topic_permission", name(consultant.getTopicPermission()));
    row.put("password_change_required", consultant.getPasswordChangeRequired());
    row.put("live_chat_via_sidebar", consultant.getLiveChatViaSidebar());
    row.put("magic_link_login_enabled", consultant.getMagicLinkLoginEnabled());
    row.put("notify_enquiries_repeating", consultant.getNotifyEnquiriesRepeating());
    row.put(
        "notify_new_chat_message_from_advice_seeker",
        consultant.getNotifyNewChatMessageFromAdviceSeeker());
    row.put("tenant_id", consultant.getTenantId());
    row.put("status", name(consultant.getStatus()));
    row.put("walk_through_enabled", consultant.getWalkThroughEnabled());
    row.put("language_code", name(consultant.getLanguageCode()));
    row.put(
        "terms_and_conditions_confirmation", time(consultant.getTermsAndConditionsConfirmation()));
    row.put("data_privacy_confirmation", time(consultant.getDataPrivacyConfirmation()));
    row.put("notifications_enabled", consultant.isNotificationsEnabled());
    row.put("notifications_settings", consultant.getNotificationsSettings());
    return original(row, codec);
  }

  private static Map<String, Object> sql(ResultSet rs) throws SQLException {
    Map<String, Object> row = new TreeMap<>();
    row.put("managed_paused", flag(rs, "managed_paused"));
    row.put("consultant_id", rs.getString("consultant_id"));
    row.put("matrix_user_id", rs.getString("matrix_user_id"));
    row.put("chat_recovery_mode", rs.getString("chat_recovery_mode"));
    row.put("chat_recovery_policy_revision", number(rs, "chat_recovery_policy_revision"));
    row.put("username", rs.getString("username"));
    row.put("first_name", rs.getString("first_name"));
    row.put("last_name", rs.getString("last_name"));
    row.put("email", rs.getString("email"));
    row.put("is_absent", flag(rs, "is_absent"));
    row.put("is_team_consultant", flag(rs, "is_team_consultant"));
    row.put("is_supervisor", flag(rs, "is_supervisor"));
    row.put("assigned_supervisor_id", rs.getString("assigned_supervisor_id"));
    row.put("display_name", rs.getString("display_name"));
    row.put("internal_display_name", rs.getString("internal_display_name"));
    row.put("salutation", rs.getString("salutation"));
    row.put("position", rs.getString("position"));
    row.put("title", rs.getString("title"));
    row.put("admin_remarks", rs.getString("admin_remarks"));
    row.put("avatar_id", rs.getString("avatar_id"));
    row.put("avatar_kind", rs.getString("avatar_kind"));
    row.put("public_slug", rs.getString("public_slug"));
    row.put("pending_public_slug", rs.getString("pending_public_slug"));
    row.put("public_slug_status", rs.getString("public_slug_status"));
    row.put(
        "public_slug_reviewed_at",
        time(
            rs.getTimestamp("public_slug_reviewed_at") == null
                ? null
                : rs.getTimestamp("public_slug_reviewed_at").toLocalDateTime()));
    row.put("absence_message", rs.getString("absence_message"));
    row.put("language_formal", flag(rs, "language_formal"));
    row.put("id_old", number(rs, "id_old"));
    row.put(
        "create_date",
        time(
            rs.getTimestamp("create_date") == null
                ? null
                : rs.getTimestamp("create_date").toLocalDateTime()));
    row.put(
        "update_date",
        time(
            rs.getTimestamp("update_date") == null
                ? null
                : rs.getTimestamp("update_date").toLocalDateTime()));
    row.put(
        "delete_date",
        time(
            rs.getTimestamp("delete_date") == null
                ? null
                : rs.getTimestamp("delete_date").toLocalDateTime()));
    row.put("deletion_lifecycle_state", rs.getString("deletion_lifecycle_state"));
    row.put(
        "deletion_read_only_until",
        time(
            rs.getTimestamp("deletion_read_only_until") == null
                ? null
                : rs.getTimestamp("deletion_read_only_until").toLocalDateTime()));
    row.put(
        "deletion_paused_until",
        time(
            rs.getTimestamp("deletion_paused_until") == null
                ? null
                : rs.getTimestamp("deletion_paused_until").toLocalDateTime()));
    row.put("deletion_pause_reason", rs.getString("deletion_pause_reason"));
    row.put("deletion_paused_by", rs.getString("deletion_paused_by"));
    row.put(
        "deletion_pause_created_at",
        time(
            rs.getTimestamp("deletion_pause_created_at") == null
                ? null
                : rs.getTimestamp("deletion_pause_created_at").toLocalDateTime()));
    row.put("encourage_2fa", flag(rs, "encourage_2fa"));
    row.put("two_factor_required", flag(rs, "two_factor_required"));
    row.put("topic_permission", rs.getString("topic_permission"));
    row.put("password_change_required", flag(rs, "password_change_required"));
    row.put("live_chat_via_sidebar", flag(rs, "live_chat_via_sidebar"));
    row.put("magic_link_login_enabled", flag(rs, "magic_link_login_enabled"));
    row.put("notify_enquiries_repeating", flag(rs, "notify_enquiries_repeating"));
    row.put(
        "notify_new_chat_message_from_advice_seeker",
        flag(rs, "notify_new_chat_message_from_advice_seeker"));
    row.put("tenant_id", number(rs, "tenant_id"));
    row.put("status", rs.getString("status"));
    row.put("walk_through_enabled", flag(rs, "walk_through_enabled"));
    row.put("language_code", rs.getString("language_code"));
    row.put(
        "terms_and_conditions_confirmation",
        time(
            rs.getTimestamp("terms_and_conditions_confirmation") == null
                ? null
                : rs.getTimestamp("terms_and_conditions_confirmation").toLocalDateTime()));
    row.put(
        "data_privacy_confirmation",
        time(
            rs.getTimestamp("data_privacy_confirmation") == null
                ? null
                : rs.getTimestamp("data_privacy_confirmation").toLocalDateTime()));
    row.put("notifications_enabled", flag(rs, "notifications_enabled"));
    row.put("notifications_settings", rs.getString("notifications_settings"));
    return row;
  }

  private static JsonNode original(Map<String, Object> row, AccountLifecycleCodec codec) {
    // Direct valueToTree: full real LOB text is MACed; generic Codec.read stays bounded.
    JsonNode full = JSON.valueToTree(row);
    String digest = codec.claimedScheduledConsultantDeletionRowDigest(full);
    Map<String, Object> original = new TreeMap<>();
    original.put("v", 1);
    original.put("consultantId", row.get("consultant_id"));
    original.put("tenantId", row.get("tenant_id"));
    original.put("createDate", row.get("create_date"));
    original.put("deleteDate", row.get("delete_date"));
    original.put("username", row.get("username"));
    original.put("matrixUserId", row.get("matrix_user_id"));
    original.put("rowDigest", digest);
    // Only the bounded eight-field core is parsed; the full SQL/LOB object above never is.
    return codec.read(codec.canonical(original), JsonNode.class);
  }

  private static Object flag(ResultSet rs, String column) throws SQLException {
    boolean value = rs.getBoolean(column);
    return rs.wasNull() ? null : value;
  }

  private static Object number(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static String time(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Consultant SQL deletion snapshot");
  }
}
