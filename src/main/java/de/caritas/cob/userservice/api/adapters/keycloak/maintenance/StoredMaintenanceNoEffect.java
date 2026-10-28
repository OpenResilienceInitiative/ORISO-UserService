package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.FrozenProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Verifies only terminal evidence for an older intent; it cannot authorize any new operation. */
final class StoredMaintenanceNoEffect {
  private StoredMaintenanceNoEffect() {}

  static void verify(
      AccountLifecycleCodec codec,
      String realmId,
      TaskActorCredentials actor,
      String account,
      Long tenant,
      String currentKind,
      UUID operation,
      String requestDigest,
      FrozenProfile original,
      String receipt) {
    if (original == null
        || original.body() == null
        || original.body().length() > 16384
        || requestDigest == null
        || !requestDigest.matches("[0-9a-f]{64}")) throw invalid();
    JsonNode body = codec.read(original.body(), JsonNode.class);
    boolean imported = "IMPORT".equals(body.path("target").path("origin").path("kind").textValue());
    String originalKind = body.path("target").path("origin").path("kind").textValue();
    String boundOperation = operation.toString();
    if (!imported
        && body.path("change").isObject()
        && body.path("change").size() == 1
        && body.path("change").has("tenantId")) {
      String child =
          UUID.nameUUIDFromBytes(
                  ("tenant-admin-edit/v1/" + operation + "/tenant")
                      .getBytes(StandardCharsets.UTF_8))
              .toString();
      if (child.equals(body.path("operationId").textValue())) boundOperation = child;
    }
    var fields = new HashSet<String>();
    body.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(
            imported
                ? Set.of("target", "workflow", "operationId", "expectedState", "change")
                : Set.of("target", "operationId", "expectedState", "change"))
        || !original.body().equals(codec.canonical(body))
        || !boundOperation.equals(body.path("operationId").textValue())
        || !account.equals(body.path("target").path("accountId").textValue())
        || !Objects.equals(
            tenant == null ? null : tenant.toString(),
            body.path("target").path("tenantId").isNull()
                ? null
                : body.path("target").path("tenantId").textValue())
        || !("IMPORT".equals(currentKind)
            ? Set.of("SELF", "HUMAN_ADMIN", "IMPORT").contains(originalKind)
            : currentKind.equals(originalKind)
                || Set.of("SELF", "HUMAN_ADMIN").contains(currentKind) && imported)
        || !boundOperation.equals(body.path("target").path("origin").path("workflowId").textValue())
        || original.bindingDigest() == null
        || !original.bindingDigest().matches("[0-9a-f]{64}")
        || !MessageDigest.isEqual(
            codec
                .digest(
                    imported
                        ? "existing-consultant-import-command/v1"
                        : "human-admin-profile-command/v1",
                    Map.of("requestDigest", requestDigest, "body", body))
                .getBytes(StandardCharsets.US_ASCII),
            original.bindingDigest().getBytes(StandardCharsets.US_ASCII))) throw invalid();
    if (imported) {
      requireFields(body.path("target"), Set.of("accountId", "tenantId", "origin"));
      JsonNode origin = body.path("target").path("origin");
      requireFields(
          origin,
          Set.of(
              "kind",
              "workflowId",
              "humanSubject",
              "humanIssuer",
              "humanClient",
              "humanAuthority"));
      for (String field : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
        if (!origin.path(field).isNull()) throw invalid();
      JsonNode workflow = body.path("workflow");
      requireFields(
          workflow,
          Set.of(
              "v", "kind", "workId", "sourceDigest", "recordNumber", "rowDigest", "requiredRoles"));
      if (!workflow.path("v").isIntegralNumber()
          || workflow.path("v").longValue() != 1
          || !"IMPORT".equals(workflow.path("kind").textValue())
          || !operation.toString().equals(workflow.path("workId").textValue())
          || !workflow.path("recordNumber").isIntegralNumber()
          || !workflow.path("recordNumber").canConvertToLong()
          || workflow.path("recordNumber").longValue() < 1) throw invalid();
      for (String field : List.of("sourceDigest", "rowDigest"))
        if (!workflow.path(field).isTextual()
            || !workflow.path(field).textValue().matches("[0-9a-f]{64}")) throw invalid();
      var roles = new TreeSet<String>();
      if (!workflow.path("requiredRoles").isArray()) throw invalid();
      for (var role : workflow.path("requiredRoles"))
        if (!role.isTextual()
            || !Set.of(
                    "consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
                .contains(role.textValue())
            || !roles.add(role.textValue())) throw invalid();
      if (!roles.contains("consultant")) throw invalid();
      requireFields(body.path("change"), Set.of("group"));
      if (!body.path("change").path("group").isTextual()
          || !body.path("change").path("group").textValue().matches("/human-roles/[a-z0-9-]{1,80}"))
        throw invalid();
      codec.verifyMaintenanceNoEffectReceipt(receipt, realmId, actor, body, "ordinary-profile");
      return;
    }
    JsonNode change = body.path("change");
    fields.clear();
    change.fieldNames().forEachRemaining(fields::add);
    String command;
    if (fields.equals(Set.of("tenantId"))
        && body.path("change").path("tenantId").isTextual()
        && body.path("change").path("tenantId").textValue().matches("[1-9][0-9]{0,18}")) {
      try {
        if (Long.parseLong(body.path("change").path("tenantId").textValue()) < 1) throw invalid();
      } catch (NumberFormatException invalid) {
        throw invalid();
      }
      command = "reassign-tenant";
    } else if (fields.isEmpty()) command = "delete";
    else if (fields.equals(Set.of("firstName", "lastName", "email"))) {
      command = "profile";
      if ("SELF".equals(originalKind)) {
        // These two fixed SELF commands have the same closed body shape. Neither grants authority;
        // the sole strict verifier must authenticate every original field before either succeeds.
        try {
          codec.verifyMaintenanceNoEffectReceipt(receipt, realmId, actor, body, "profile");
        } catch (IllegalArgumentException wrongProfileReceipt) {
          codec.verifyMaintenanceNoEffectReceipt(receipt, realmId, actor, body, "email");
        }
        return;
      }
    } else if (fields.equals(Set.of("group", "profile"))) command = "ordinary-profile";
    else if (fields.equals(Set.of("transition", "group"))
        && Set.of(
                "ADD_AGENCY_ADMIN_IDENTITY",
                "ADD_CONSULTANT_IDENTITY",
                "REMOVE_ADMINISTRATOR_IDENTITY")
            .contains(change.path("transition").textValue())
        && change.path("group").isTextual()) command = "profile-transition";
    else if (fields.equals(Set.of("transition", "profileId", "profileVersion"))
        && "ADD_CONSULTANT_IDENTITY".equals(change.path("transition").textValue())
        && change.path("profileId").isTextual()
        && change.path("profileVersion").isIntegralNumber()
        && change.path("profileVersion").canConvertToLong()
        && change.path("profileVersion").longValue() > 0) command = "profile-transition";
    else throw invalid();
    codec.verifyMaintenanceNoEffectReceipt(receipt, realmId, actor, body, command);
  }

  private static void requireFields(JsonNode node, Set<String> expected) {
    var fields = new HashSet<String>();
    node.fieldNames().forEachRemaining(fields::add);
    if (!node.isObject() || !fields.equals(expected)) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid original maintenance terminal evidence");
  }
}
