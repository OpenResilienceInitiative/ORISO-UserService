package de.caritas.cob.userservice.api.workflow.delete.service;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed Administrator rows to preserve; this compact SQL evidence grants no native authority. */
final class AdministratorPreservation {
  private static final Set<String> FIELDS =
      Set.of(
          "v",
          "accountId",
          "tenantId",
          "type",
          "username",
          "createDate",
          "rowDigest",
          "relationCount",
          "relationsDigest");

  static JsonNode capture(
      JdbcTemplate jdbc, AccountLifecycleCodec codec, String account, boolean lock) {
    var rows =
        jdbc.queryForList(
            "select * from admin where admin_id=?" + (lock ? " for update" : ""), account);
    if (rows.isEmpty()) return null;
    if (rows.size() != 1) throw invalid();
    var row = normalized(rows.getFirst());
    var relations =
        jdbc
            .queryForList(
                "select * from admin_agency where admin_id=? order by id"
                    + (lock ? " for update" : ""),
                account)
            .stream()
            .map(AdministratorPreservation::normalized)
            .toList();
    var core = new TreeMap<String, Object>();
    core.put("v", 1);
    core.put("accountId", account);
    core.put("tenantId", row.get("tenant_id"));
    core.put("type", row.get("type"));
    core.put("username", row.get("username"));
    core.put("createDate", row.get("create_date"));
    core.put(
        "rowDigest",
        codec.digest("public-pre-deletion-row/v1", Map.of("kind", "PRESERVED_ADMIN", "row", row)));
    core.put("relationCount", relations.size());
    core.put(
        "relationsDigest",
        codec.digest(
            "public-pre-deletion-row/v1",
            Map.of("kind", "PRESERVED_ADMIN_AGENCY", "rows", relations)));
    return require(codec.read(codec.canonical(core), JsonNode.class));
  }

  static JsonNode require(JsonNode value) {
    var fields = new HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(FIELDS)
        || !value.path("v").isIntegralNumber()
        || value.path("v").intValue() != 1
        || !value.path("accountId").isTextual()
        || !UUID.fromString(value.path("accountId").textValue())
            .toString()
            .equals(value.path("accountId").textValue())
        || !Set.of("AGENCY", "TENANT", "SUPER").contains(value.path("type").asText())
        || !value.path("username").isTextual()
        || value.path("username").asText().isBlank()
        || !value.path("createDate").isTextual()
        || java.time.Instant.parse(value.path("createDate").textValue()).getEpochSecond() <= 0
        || !value.path("relationCount").isIntegralNumber()
        || value.path("relationCount").longValue() < 0
        || !value.path("rowDigest").asText().matches("[0-9a-f]{64}")
        || !value.path("relationsDigest").asText().matches("[0-9a-f]{64}")
        || !value.path("tenantId").isNull()
            && (!value.path("tenantId").isIntegralNumber()
                || !value.path("tenantId").canConvertToLong()
                || value.path("tenantId").longValue() < 0)) throw invalid();
    return value;
  }

  static boolean sameBirth(JsonNode original, JsonNode current) {
    if (original == null || current == null) return false;
    require(original);
    require(current);
    for (String field : List.of("accountId", "tenantId", "type", "username", "createDate"))
      if (!original.path(field).equals(current.path(field))) return false;
    return true;
  }

  private static Map<String, Object> normalized(Map<String, Object> row) {
    var result = new TreeMap<String, Object>();
    row.forEach(
        (name, value) ->
            result.put(
                name.toLowerCase(Locale.ROOT),
                value instanceof java.sql.Timestamp timestamp
                    ? timestamp.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toString()
                    : value instanceof Number number ? number.longValue() : value));
    return result;
  }

  private static ConflictException invalid() {
    return new ConflictException("Administrator preservation source changed");
  }

  private AdministratorPreservation() {}
}
