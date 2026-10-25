package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Clob;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed nonsecret User29/Consultant53 and coherent Admin11 DATA; never native authority. */
final class SelfContactEmailSource {
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

  private static final Set<String> ADMIN =
      Set.of(
          "managed_paused",
          "admin_id",
          "tenant_id",
          "username",
          "first_name",
          "last_name",
          "email",
          "type",
          "id_old",
          "create_date",
          "update_date");

  static JsonNode capture(
      JdbcTemplate jdbc, AccountLifecycleCodec codec, String account, Long tenant) {
    var owner = SelfAccountOwner.inspect(jdbc, account, tenant);
    if (owner.kind().admin() && !owner.consultant()) throw invalid();
    ObjectNode source = codec.read("{}", ObjectNode.class);
    source.put("v", 1).put("kind", owner.kind().name());
    if (owner.kind() == SelfAccountOwner.Kind.USER)
      source.set("user", row(jdbc, codec, "user", "user_id", USER, account));
    else source.putNull("user");
    if (owner.kind() == SelfAccountOwner.Kind.CONSULTANT || owner.consultant())
      source.set(
          "consultant", row(jdbc, codec, "consultant", "consultant_id", CONSULTANT, account));
    else source.putNull("consultant");
    if (owner.kind().admin())
      source.set("admin", row(jdbc, codec, "admin", "admin_id", ADMIN, account));
    else source.putNull("admin");
    return source;
  }

  private static JsonNode row(
      JdbcTemplate jdbc,
      AccountLifecycleCodec codec,
      String table,
      String identifier,
      Set<String> fields,
      String account) {
    var rows =
        jdbc.query(
            "select "
                + String.join(",", new java.util.TreeSet<>(fields))
                + " from "
                + table
                + " where "
                + identifier
                + "=? for update",
            (rs, index) -> {
              var value = new TreeMap<String, Object>();
              for (String field : fields) {
                Object current;
                if (TIMES.contains(field)) {
                  var time = rs.getObject(field, LocalDateTime.class);
                  current = time == null ? null : time.toString();
                } else if (NUMBERS.contains(field)) {
                  long number = rs.getLong(field);
                  current = rs.wasNull() ? null : number;
                } else if (FLAGS.contains(field)) {
                  boolean flag = rs.getBoolean(field);
                  current = rs.wasNull() ? null : flag;
                } else {
                  current = rs.getObject(field);
                  if (current instanceof Clob clob) {
                    if (clob.length() > 1048576) throw invalid();
                    current = clob.getSubString(1, Math.toIntExact(clob.length()));
                  }
                  if (current != null
                      && (!(current instanceof String text) || text.length() > 1048576))
                    throw invalid();
                }
                value.put(field, current);
              }
              return codec.read(codec.canonical(value), JsonNode.class);
            },
            account);
    if (rows.size() != 1) throw invalid();
    return rows.getFirst();
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Contact email source unavailable");
  }

  private SelfContactEmailSource() {}
}
