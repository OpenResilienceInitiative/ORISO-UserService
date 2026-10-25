package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Fixed nonsecret result data. Valid JSON cannot issue a held grant or Matrix ownership. */
final class ConsultantGrantMatrixSchema {
  private static final Set<String> FIELDS =
      Set.of(
          "representation",
          "operationId",
          "accountId",
          "requestDigest",
          "localpart",
          "phase",
          "result");

  private ConsultantGrantMatrixSchema() {}

  static JsonNode require(JsonNode value) {
    if (value == null || !value.isObject() || value.size() != FIELDS.size()) throw invalid();
    var fields = new HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!FIELDS.equals(fields)
        || !"CLAIMED_CONSULTANT_GRANT_MATRIX_V1".equals(text(value.path("representation"), 64)))
      throw invalid();
    uuid(value.path("operationId"));
    uuid(value.path("accountId"));
    if (!text(value.path("requestDigest"), 64).matches("[0-9a-f]{64}")) throw invalid();
    String localpart = text(value.path("localpart"), 255);
    if (!localpart.equals(localpart.toLowerCase(Locale.ROOT))
        || localpart.contains("@")
        || localpart.contains(":")) throw invalid();
    String phase = text(value.path("phase"), 32);
    if (!Set.of("STARTED", "ACKNOWLEDGED", "NO_EFFECT").contains(phase)) throw invalid();
    if ("ACKNOWLEDGED".equals(phase)) {
      String result = text(value.path("result"), 512), prefix = "@" + localpart + ":";
      if (!result.startsWith(prefix) || result.substring(prefix.length()).isBlank())
        throw invalid();
    } else if (!value.path("result").isNull()) throw invalid();
    return value;
  }

  private static void uuid(JsonNode node) {
    String value = text(node, 36);
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw invalid();
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static String text(JsonNode node, int maximum) {
    if (!node.isTextual()
        || node.textValue().isBlank()
        || node.textValue().length() > maximum
        || node.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return node.textValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid consultant grant Matrix result metadata");
  }
}
