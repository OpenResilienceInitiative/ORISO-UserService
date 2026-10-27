package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Closed ACK-only custody data; only the actual owner and private preparation supply its values.
 */
final class PreDeletionRelatedCustody {
  static final String DOMAIN = "public-pre-deletion-related/v1";

  private PreDeletionRelatedCustody() {}

  static ObjectNode binding(
      AccountLifecycleCodec codec,
      String operation,
      String account,
      String client,
      String subject,
      String sourceMac,
      String body,
      String receipt) {
    return codec.read(
        codec.canonical(
            Map.of(
                "operationId",
                operation,
                "accountId",
                account,
                "ownerClient",
                client,
                "ownerSubject",
                subject,
                "sourceMac",
                sourceMac,
                "intentDigest",
                codec.digest("maintenance-intent", codec.read(body, JsonNode.class)),
                "receiptDigest",
                codec.digest(DOMAIN, Map.of("receipt", receipt)))),
        ObjectNode.class);
  }

  static String pending(AccountLifecycleCodec codec, JsonNode binding) {
    return freeze(codec, binding, codec.read("{\"state\":\"PENDING\"}", ObjectNode.class));
  }

  static String freeze(AccountLifecycleCodec codec, JsonNode binding, JsonNode plan) {
    requirePlan(plan);
    var value =
        codec.read(
            codec.canonical(
                Map.of(
                    "v", 1, "kind", "PRE_DELETION_RELATED_V1", "binding", binding, "plan", plan)),
            ObjectNode.class);
    value.put("mac", codec.digest(DOMAIN, value));
    return codec.canonical(value);
  }

  static JsonNode require(AccountLifecycleCodec codec, String encoded, JsonNode binding) {
    try {
      var value = codec.read(encoded, ObjectNode.class);
      fields(value, Set.of("v", "kind", "binding", "plan", "mac"));
      if (!value.path("v").isIntegralNumber()
          || value.path("v").intValue() != 1
          || !value.path("v").canConvertToInt()
          || !"PRE_DELETION_RELATED_V1".equals(value.path("kind").textValue())
          || !binding.equals(value.path("binding"))) throw unavailable();
      String mac = value.path("mac").textValue();
      value.remove("mac");
      if (mac == null || !mac.matches("[0-9a-f]{64}") || !mac.equals(codec.digest(DOMAIN, value)))
        throw unavailable();
      requirePlan(value.path("plan"));
      return value.path("plan");
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private static void requirePlan(JsonNode plan) {
    if ("PENDING".equals(plan.path("state").textValue())) {
      fields(plan, Set.of("state"));
      return;
    }
    fields(plan, Set.of("state", "rows", "remotes"));
    if (!"FROZEN".equals(plan.path("state").textValue())) throw unavailable();
    fields(
        plan.path("rows"),
        Set.of("chat", "participant", "session", "supervision", "consultant", "agency"));
    plan.path("rows").elements().forEachRemaining(PreDeletionRelatedCustody::requireDigest);
    requireDigest(plan.path("remotes"));
  }

  private static void requireDigest(JsonNode value) {
    fields(value, Set.of("count", "digest"));
    if (!value.path("count").isIntegralNumber()
        || !value.path("count").canConvertToLong()
        || value.path("count").longValue() < 0
        || !value.path("digest").isTextual()
        || !value.path("digest").textValue().matches("[0-9a-f]{64}")) throw unavailable();
  }

  private static void fields(JsonNode value, Set<String> expected) {
    var fields = new HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!value.isObject() || !fields.equals(expected)) throw unavailable();
  }

  private static ServiceUnavailableException unavailable() {
    return new ServiceUnavailableException("Original related pre-deletion custody is unavailable");
  }
}
