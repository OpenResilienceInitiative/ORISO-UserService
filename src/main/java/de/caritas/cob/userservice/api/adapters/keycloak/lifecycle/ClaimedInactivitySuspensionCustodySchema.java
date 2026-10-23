package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Closed nonsecret source data only. Native proof and actual SQL ownership remain independent. */
final class ClaimedInactivitySuspensionCustodySchema {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private ClaimedInactivitySuspensionCustodySchema() {}

  static JsonNode require(JsonNode value, String originalRowDigest) {
    fields(
        value,
        Set.of("representation", "originalRow", "source", "nativeBody", "nativeReceipt", "phase"));
    if (!"CLAIMED_INACTIVITY_SUSPENSION_CUSTODY_V1".equals(text(value.path("representation"), 64)))
      throw invalid();
    JsonNode row = ClaimedInactivityRowSchema.require(value.path("originalRow"), false);
    if (!"SUSPEND".equals(row.path("action").textValue())) throw invalid();
    source(value.path("source"), row);
    String phase = text(value.path("phase"), 20);
    if ("CAPTURED".equals(phase)) {
      if (!value.path("nativeBody").isNull() || !value.path("nativeReceipt").isNull())
        throw invalid();
      return value;
    }
    if (!Set.of("FROZEN", "ACKNOWLEDGED", "NO_EFFECT").contains(phase)) throw invalid();
    body(value.path("nativeBody"), row, originalRowDigest);
    if ("FROZEN".equals(phase)) {
      if (!value.path("nativeReceipt").isNull()) throw invalid();
    } else receipt(value.path("nativeReceipt"), value.path("nativeBody"), row, phase);
    return value;
  }

  private static void source(JsonNode source, JsonNode row) {
    fields(source, Set.of("user", "consultant", "admin"));
    for (String table : List.of("user", "consultant", "admin")) {
      var rows = source.path(table);
      if (!rows.isArray() || rows.size() > 1) throw invalid();
      for (var item : rows) {
        String id = table.equals("user") ? "user_id" : table + "_id";
        var expected = new HashSet<>(Set.of(id, "tenant_id", "username", "create_date"));
        if (table.equals("admin")) expected.add("type");
        else {
          expected.addAll(Set.of("delete_date", "deletion_lifecycle_state", "matrix_user_id"));
          if (table.equals("consultant")) expected.add("status");
        }
        fields(item, expected);
        if (!row.path("identityId").textValue().equals(uuid(item.path(id)))) throw invalid();
        if (row.path("tenantId").isNull()) {
          if (!item.path("tenant_id").isNull()) throw invalid();
        } else if (!item.path("tenant_id").isIntegralNumber()
            || !item.path("tenant_id").canConvertToLong()
            || !row.path("tenantId")
                .textValue()
                .equals(Long.toString(item.path("tenant_id").longValue()))) throw invalid();
        text(item.path("username"), 255);
        timestamp(item.path("create_date"), false);
        if (table.equals("admin")) {
          try {
            de.caritas.cob.userservice.api.model.Admin.AdminType.valueOf(
                text(item.path("type"), 20));
          } catch (IllegalArgumentException invalid) {
            throw invalid();
          }
        } else {
          timestamp(item.path("delete_date"), true);
          if (!item.path("matrix_user_id").isNull()) text(item.path("matrix_user_id"), 255);
          try {
            de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.valueOf(
                text(item.path("deletion_lifecycle_state"), 64));
            if (table.equals("consultant"))
              de.caritas.cob.userservice.api.model.ConsultantStatus.valueOf(
                  text(item.path("status"), 20));
          } catch (IllegalArgumentException invalid) {
            throw invalid();
          }
        }
      }
    }
  }

  private static void body(JsonNode body, JsonNode row, String digest) {
    fields(body, Set.of("target", "workflow", "operationId", "expectedState", "change"));
    fields(body.path("target"), Set.of("accountId", "tenantId", "origin"));
    var target = body.path("target");
    if (!row.path("identityId").textValue().equals(uuid(target.path("accountId")))
        || !row.path("workId").textValue().equals(uuid(body.path("operationId")))
        || !row.path("tenantId").equals(target.path("tenantId"))
        || !text(body.path("expectedState"), 64).matches("[0-9a-f]{64}")
        || !body.path("change").isObject()
        || !body.path("change").isEmpty()) throw invalid();
    var origin = target.path("origin");
    fields(
        origin,
        Set.of(
            "kind", "workflowId", "humanSubject", "humanIssuer", "humanClient", "humanAuthority"));
    if (!row.path("workId").textValue().equals(uuid(origin.path("workflowId")))) throw invalid();
    var initiator = row.path("initiator");
    if ("SCHEDULED".equals(initiator.path("kind").textValue())) {
      if (!"INACTIVITY".equals(origin.path("kind").textValue())) throw invalid();
      for (String name : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
        if (!origin.path(name).isNull()) throw invalid();
    } else if (!"HUMAN_ADMIN".equals(origin.path("kind").textValue())
        || !initiator.path("subject").equals(origin.path("humanSubject"))
        || !initiator.path("issuer").equals(origin.path("humanIssuer"))
        || !initiator.path("client").equals(origin.path("humanClient"))
        || !"PROTECTED_ACCOUNT_OPERATIONS_V1".equals(origin.path("humanAuthority").textValue()))
      throw invalid();
    var workflow = body.path("workflow");
    fields(
        workflow,
        Set.of(
            "v",
            "kind",
            "workId",
            "action",
            "rowDigest",
            "revision",
            "assignedMonths",
            "lastActivity",
            "dueAt",
            "suspensionOperationId"));
    equalInteger(workflow.path("v"), 1);
    equalInteger(workflow.path("revision"), row.path("revision").longValue());
    equalInteger(workflow.path("assignedMonths"), row.path("assignedMonths").longValue());
    equalInteger(
        workflow.path("lastActivity"), row.path("lastActivityMicros").longValue() / 1_000_000L);
    equalInteger(workflow.path("dueAt"), row.path("dueAtMicros").longValue() / 1_000_000L);
    if (!"INACTIVITY".equals(workflow.path("kind").textValue())
        || !"SUSPEND".equals(workflow.path("action").textValue())
        || !row.path("workId").textValue().equals(uuid(workflow.path("workId")))
        || !text(workflow.path("rowDigest"), 64).matches("[0-9a-f]{64}")
        || digest != null && !digest.equals(workflow.path("rowDigest").textValue())
        || !workflow.path("suspensionOperationId").isNull()) throw invalid();
  }

  private static void receipt(JsonNode value, JsonNode body, JsonNode row, String phase) {
    String raw = text(value, 8192);
    try {
      JsonNode reply = JSON.readTree(raw);
      boolean refused = "NO_EFFECT".equals(phase);
      fields(
          reply,
          refused
              ? Set.of("operationId", "accountId", "result", "status", "receipt")
              : Set.of("operationId", "accountId", "result", "receipt"));
      String result = refused ? "NO_EFFECT" : "UPDATED";
      if (!row.path("identityId").textValue().equals(uuid(reply.path("accountId")))
          || !row.path("workId").textValue().equals(uuid(reply.path("operationId")))
          || !result.equals(reply.path("result").textValue())) throw invalid();
      String token = text(reply.path("receipt"), 8192);
      if (!token.matches("[A-Za-z0-9_-]+\\.[0-9a-f]{64}")) throw invalid();
      var payload =
          JSON.readTree(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))));
      var keys =
          new HashSet<>(
              Set.of(
                  "realmId",
                  "accountId",
                  "operationId",
                  "command",
                  "ownerClient",
                  "ownerSubject",
                  "intentDigest",
                  "previousState",
                  "tenantId",
                  "originDigest",
                  "result"));
      if (refused) keys.add("status");
      fields(payload, keys);
      if (!reply.path("accountId").equals(payload.path("accountId"))
          || !reply.path("operationId").equals(payload.path("operationId"))
          || !result.equals(payload.path("result").textValue())
          || !"deactivate".equals(payload.path("command").textValue())
          || !body.path("expectedState").equals(payload.path("previousState"))
          || !row.path("tenantId").equals(payload.path("tenantId"))) throw invalid();
      text(payload.path("realmId"), 255);
      text(payload.path("ownerClient"), 255);
      uuid(payload.path("ownerSubject"));
      for (String name : List.of("intentDigest", "originDigest"))
        if (!text(payload.path(name), 64).matches("[0-9a-f]{64}")) throw invalid();
      if (refused) {
        if (!reply.path("status").isIntegralNumber()
            || !reply.path("status").canConvertToInt()
            || !Set.of(403, 409).contains(reply.path("status").intValue())) throw invalid();
        equalInteger(payload.path("status"), reply.path("status").intValue());
      }
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw invalid();
    }
  }

  private static void equalInteger(JsonNode value, long expected) {
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() != expected)
      throw invalid();
  }

  private static String uuid(JsonNode node) {
    String value = text(node, 36);
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw invalid();
    } catch (IllegalArgumentException invalid) {
      throw invalid();
    }
    return value;
  }

  private static void timestamp(JsonNode node, boolean nullable) {
    if (nullable && node.isNull()) return;
    String value = text(node, 64);
    try {
      var parsed = Instant.parse(value);
      if (parsed.getEpochSecond() <= 0
          || parsed.getNano() % 1000 != 0
          || !parsed.toString().equals(value)) throw invalid();
    } catch (java.time.DateTimeException invalid) {
      throw invalid();
    }
  }

  private static String text(JsonNode node, int max) {
    if (!node.isTextual()
        || node.textValue().isBlank()
        || node.textValue().length() > max
        || node.textValue().chars().anyMatch(Character::isISOControl)) throw invalid();
    return node.textValue();
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
    var names = new HashSet<String>();
    node.fieldNames().forEachRemaining(names::add);
    if (!expected.equals(names)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid original suspension source custody");
  }
}
