package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

/** Data validation for the two fixed owning-row MAC purposes; it creates no authority. */
final class ClaimedInactivityRowSchema {
  private ClaimedInactivityRowSchema() {}

  static JsonNode require(JsonNode row, boolean deleting) {
    fields(
        row,
        Set.of(
            "identityId",
            "tenantId",
            "assignedMonths",
            "revision",
            "lastActivityMicros",
            "dueAtMicros",
            "status",
            "attempts",
            "lastError",
            "workId",
            "action",
            "initiator",
            "suspensionOperationId",
            "claimedAtMicros"));
    uuid(row.path("identityId"));
    uuid(row.path("workId"));
    JsonNode tenant = row.path("tenantId");
    if (!tenant.isNull()) {
      if (!tenant.isTextual() || !tenant.textValue().matches("0|[1-9][0-9]{0,18}")) throw invalid();
      try {
        Long.parseLong(tenant.textValue());
      } catch (NumberFormatException invalid) {
        throw invalid();
      }
    }
    integer(row.path("assignedMonths"), true, true);
    integer(row.path("attempts"), true, false);
    integer(row.path("revision"), false, false);
    for (String field : List.of("lastActivityMicros", "dueAtMicros", "claimedAtMicros"))
      integer(row.path(field), false, true);
    if (row.path("dueAtMicros").longValue() < row.path("lastActivityMicros").longValue())
      throw invalid();
    JsonNode error = row.path("lastError");
    if (!error.isNull()
        && (!error.isTextual()
            || error.textValue().length() > 1024
            || !error.textValue().matches("[A-Z][A-Z0-9_]*(?::[A-Z][A-Z0-9_]*)?"))) throw invalid();
    String action = text(row.path("action"), 16), status = text(row.path("status"), 20);
    if (deleting) {
      if (!action.equals("DELETE")
          || !status.equals("DELETING")
          || !row.path("suspensionOperationId").isNull()) throw invalid();
    } else if (action.equals("SUSPEND")) {
      if (!status.equals("SUSPENDING") || !row.path("suspensionOperationId").isNull())
        throw invalid();
    } else if (action.equals("RESTORE")) {
      if (!status.equals("REACTIVATING")) throw invalid();
      uuid(row.path("suspensionOperationId"));
    } else throw invalid();
    JsonNode initiator = row.path("initiator");
    String kind = text(initiator.path("kind"), 20);
    if (kind.equals("SCHEDULED")) fields(initiator, Set.of("kind"));
    else if (kind.equals("HUMAN_ADMIN")) {
      fields(initiator, Set.of("kind", "subject", "issuer", "client"));
      uuid(initiator.path("subject"));
      String client = text(initiator.path("client"), 255);
      if (!client.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}")) throw invalid();
      try {
        URI issuer = URI.create(text(initiator.path("issuer"), 2048));
        if (!Set.of("http", "https").contains(issuer.getScheme())
            || issuer.getHost() == null
            || issuer.getRawUserInfo() != null
            || issuer.getRawQuery() != null
            || issuer.getRawFragment() != null) throw invalid();
      } catch (RuntimeException invalid) {
        throw invalid();
      }
    } else throw invalid();
    return row;
  }

  private static void fields(JsonNode value, Set<String> fields) {
    if (value == null || !value.isObject() || value.size() != fields.size()) throw invalid();
    var actual = new HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(fields)) throw invalid();
  }

  private static void integer(JsonNode value, boolean narrow, boolean positive) {
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || narrow && !value.canConvertToInt()
        || positive && value.longValue() <= 0
        || !positive && value.longValue() < 0) throw invalid();
  }

  private static void uuid(JsonNode value) {
    String text = text(value, 36);
    try {
      if (!UUID.fromString(text).toString().equals(text)) throw invalid();
    } catch (RuntimeException invalid) {
      throw invalid();
    }
  }

  private static String text(JsonNode value, int max) {
    if (!value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > max
        || value.textValue().chars().anyMatch(Character::isISOControl)) throw invalid();
    return value.textValue();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid owned inactivity row");
  }
}
