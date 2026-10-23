package de.caritas.cob.userservice.api.workflow.accountinactivity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Snapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL metadata only. It cannot construct a live claimed-work capability. */
final class InactivityClaimJournal {
  record Saved(
      String id,
      String work,
      String claimed,
      String source,
      String phase,
      String body,
      String receipt,
      String suspension,
      Instant claimedAt) {
    @Override
    public String toString() {
      return "Saved[claimed-work=redacted]";
    }
  }

  private final JdbcTemplate jdbc;
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  InactivityClaimJournal(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Saved saved(String id, boolean lock) {
    return jdbc
        .query(
            "select identity_id,work_id,claimed_json,source_json,native_phase,native_body,native_receipt,suspension_operation_id,claimed_at from account_inactivity where identity_id=?"
                + (lock ? " for update" : ""),
            (r, n) ->
                new Saved(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6),
                    r.getString(7),
                    r.getString(8),
                    r.getTimestamp(9) == null ? null : r.getTimestamp(9).toInstant()),
            id)
        .stream()
        .findFirst()
        .orElseThrow();
  }

  Saved claim(
      Snapshot row, String action, Map<String, Object> initiator, Instant now, String suspension) {
    // MySQL DATETIME(6) is the owning timestamp precision; persist the same original microseconds.
    now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    String status =
        switch (action) {
          case "SUSPEND" -> "SUSPENDING";
          case "RESTORE" -> "REACTIVATING";
          case "DELETE" -> "DELETING";
          default -> throw invalid();
        };
    String work = UUID.randomUUID().toString();
    Map<String, Object> binding = new TreeMap<>();
    binding.put("identityId", row.identityId());
    binding.put("tenantId", row.tenantId() == null ? null : row.tenantId().toString());
    binding.put("assignedMonths", row.assignedMonths());
    binding.put("revision", row.revision());
    binding.put("lastActivityMicros", micros(row.lastActivity()));
    binding.put("dueAtMicros", micros(row.dueAt()));
    binding.put("status", status);
    binding.put("attempts", row.attempts());
    binding.put("lastError", row.lastError());
    binding.put("workId", work);
    binding.put("action", action);
    binding.put("initiator", initiator);
    binding.put("suspensionOperationId", suspension);
    binding.put("claimedAtMicros", micros(now));
    String original = encode(binding), source = source(row.identityId(), row.tenantId(), true);
    if (jdbc.update(
            "update account_inactivity set status=?,work_id=?,claimed_at=?,claimed_json=?,source_json=?,native_phase='CLAIMED',native_body=NULL,native_receipt=NULL,restore_effects_json=NULL,last_error=NULL where identity_id=?",
            status,
            work,
            Timestamp.from(now),
            original,
            source,
            row.identityId())
        != 1) throw invalid();
    return saved(row.identityId(), true);
  }

  String source(String id, Long tenant, boolean lock) {
    var result = new TreeMap<String, Object>();
    for (String table : List.of("user", "consultant", "admin")) {
      String key = table.equals("user") ? "user_id" : table + "_id";
      var values =
          jdbc.queryForList(
              "select * from `" + table + "` where " + key + "=?" + (lock ? " for update" : ""),
              id);
      if (values.size() > 1) throw invalid();
      var rows = new ArrayList<Object>();
      for (var row : values) {
        Object rowTenant = row.get("tenant_id");
        Long actual = rowTenant == null ? null : ((Number) rowTenant).longValue();
        if (!Objects.equals(tenant, actual)) throw invalid();
        var selected = new TreeMap<String, Object>();
        for (String field :
            List.of(
                key,
                "tenant_id",
                "username",
                "create_date",
                "delete_date",
                "type",
                "status",
                "deletion_lifecycle_state",
                "matrix_user_id")) {
          if (!row.containsKey(field)) continue;
          Object value = row.get(field);
          selected.put(
              field,
              value instanceof Timestamp timestamp ? timestamp.toInstant().toString() : value);
        }
        rows.add(selected);
      }
      result.put(table, rows);
    }
    return encode(result);
  }

  JsonNode decode(String value) {
    try {
      return json.readTree(value);
    } catch (Exception invalid) {
      throw invalid();
    }
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw invalid();
    }
  }

  static long micros(Instant value) {
    return Math.addExact(
        Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1000);
  }

  static IllegalStateException invalid() {
    return new IllegalStateException("Owned inactivity claim is unavailable");
  }
}
