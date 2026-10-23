package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Closed nonsecret data validation only. No JSON value can issue live deletion authority. */
final class ClaimedDeletionIntentSchema {
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

  private ClaimedDeletionIntentSchema() {}

  static JsonNode scheduled(JsonNode envelope) {
    keys(envelope, Set.of("representation", "originalSql", "body"));
    if (!"CLAIMED_SCHEDULED_ASKER_DELETE_V1"
        .equals(text(envelope.path("representation"), 64, false))) throw invalid();
    var binding = body(envelope.path("body"));
    user(envelope.path("originalSql"), binding, true);
    return envelope;
  }

  static JsonNode inactivity(JsonNode envelope, String actualRowDigest) {
    keys(envelope, Set.of("representation", "originalRow", "rowDigest", "originalSql", "body"));
    if (!"CLAIMED_INACTIVITY_DELETE_V1".equals(text(envelope.path("representation"), 64, false))
        || !Objects.equals(actualRowDigest, text(envelope.path("rowDigest"), 64, false)))
      throw invalid();
    var row = ClaimedInactivityRowSchema.require(envelope.path("originalRow"), true);
    var binding = body(envelope.path("body"));
    if (!binding.account.equals(row.path("identityId").textValue())
        || !binding.operation.equals(row.path("workId").textValue())
        || !Objects.equals(
            binding.tenant,
            row.path("tenantId").isNull() ? null : row.path("tenantId").textValue()))
      throw invalid();
    // Null means only captured absence data. The private actual owner independently proves it.
    if (!envelope.path("originalSql").isNull()) user(envelope.path("originalSql"), binding, false);
    return envelope;
  }

  private record Binding(String account, String tenant, String operation) {}

  private static Binding body(JsonNode body) {
    keys(body, Set.of("target", "operationId", "expectedState", "change"));
    keys(body.path("target"), Set.of("accountId", "tenantId", "origin"));
    var target = body.path("target");
    String account = uuid(target.path("accountId")), operation = uuid(body.path("operationId"));
    String tenant =
        target.path("tenantId").isNull() ? null : text(target.path("tenantId"), 20, false);
    if (tenant != null) {
      try {
        if (!Long.toString(Long.parseLong(tenant)).equals(tenant) || Long.parseLong(tenant) < 0)
          throw invalid();
      } catch (NumberFormatException invalid) {
        throw invalid();
      }
    }
    var origin = target.path("origin");
    keys(
        origin,
        Set.of(
            "kind", "workflowId", "humanSubject", "humanIssuer", "humanClient", "humanAuthority"));
    if (!"DELETION".equals(text(origin.path("kind"), 32, false))
        || !operation.equals(uuid(origin.path("workflowId")))
        || !text(body.path("expectedState"), 64, false).matches("[0-9a-f]{64}")
        || !body.path("change").isObject()
        || !body.path("change").isEmpty()) throw invalid();
    for (String name : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      if (!origin.path(name).isNull()) throw invalid();
    return new Binding(account, tenant, operation);
  }

  private static void user(JsonNode row, Binding binding, boolean scheduled) {
    keys(row, USER);
    if (!binding.account.equals(uuid(row.path("user_id")))) throw invalid();
    if (binding.tenant == null) {
      if (!row.path("tenant_id").isNull()) throw invalid();
    } else if (!row.path("tenant_id").isIntegralNumber()
        || !row.path("tenant_id").canConvertToLong()
        || !binding.tenant.equals(Long.toString(row.path("tenant_id").longValue())))
      throw invalid();
    String state = text(row.path("deletion_lifecycle_state"), 64, false);
    try {
      de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.valueOf(state);
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
    if (scheduled && !"HARD_DELETE_IN_PROGRESS".equals(state)) throw invalid();
    text(row.path("username"), 255, false);
    text(row.path("email"), 255, false);
    nullableText(row.path("matrix_user_id"), 255);
    nullableText(row.path("deletion_pause_reason"), 512);
    nullableText(row.path("deletion_paused_by"), 64);
    nullableText(row.path("notifications_settings"), 4000);
    nullableText(row.path("avatar_id"), 40);
    nullableLong(row.path("id_old"), false);
    nullableLong(row.path("chat_recovery_policy_revision"), true);
    if (!row.path("chat_recovery_mode").isNull()
        && !Set.of("LOGIN_PASSWORD", "RECOVERY_KEY")
            .contains(text(row.path("chat_recovery_mode"), 32, false))) throw invalid();
    if (!text(row.path("language_code"), 2, false).matches("[a-z]{2}")) throw invalid();
    for (String name : List.of("language_formal", "notifications_enabled", "temporary_account"))
      if (!row.path(name).isBoolean()) throw invalid();
    for (String name : List.of("encourage_2fa", "magic_link_login_enabled"))
      if (!row.path(name).isNull() && !row.path(name).isBoolean()) throw invalid();
    if (!row.path("reactivation_operation_id").isNull())
      uuid(row.path("reactivation_operation_id"));
    timestamp(row.path("create_date"), false);
    timestamp(row.path("delete_date"), !scheduled);
    for (String name :
        List.of(
            "update_date",
            "deletion_read_only_until",
            "deletion_paused_until",
            "deletion_pause_created_at",
            "reactivation_operation_started_at",
            "terms_and_conditions_confirmation",
            "data_privacy_confirmation")) timestamp(row.path(name), true);
  }

  private static void timestamp(JsonNode node, boolean nullable) {
    if (nullable && node.isNull()) return;
    String value = text(node, 64, false);
    try {
      var parsed = Instant.parse(value);
      if (parsed.getEpochSecond() <= 0
          || parsed.getNano() % 1000 != 0
          || !parsed.toString().equals(value)) throw invalid();
    } catch (java.time.DateTimeException invalid) {
      throw invalid();
    }
  }

  private static void nullableLong(JsonNode node, boolean nonnegative) {
    if (node.isNull()) return;
    if (!node.isIntegralNumber() || !node.canConvertToLong() || nonnegative && node.longValue() < 0)
      throw invalid();
  }

  private static void nullableText(JsonNode node, int limit) {
    if (!node.isNull()) text(node, limit, true);
  }

  private static String text(JsonNode node, int limit, boolean empty) {
    if (!node.isTextual()
        || node.textValue().length() > limit
        || !empty && node.textValue().isBlank()
        || node.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return node.textValue();
  }

  private static String uuid(JsonNode node) {
    String value = text(node, 36, false);
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw invalid();
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
    return value;
  }

  private static void keys(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
    var actual = new HashSet<String>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!expected.equals(actual)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid owned deletion intent metadata");
  }
}
