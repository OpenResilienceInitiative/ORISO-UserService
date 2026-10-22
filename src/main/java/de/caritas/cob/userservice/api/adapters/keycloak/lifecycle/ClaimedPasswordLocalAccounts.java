package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Loads the complete actual incarnation under the caller's owning database transaction. */
final class ClaimedPasswordLocalAccounts {
  private final JdbcTemplate jdbc;
  private final AccountLifecycleCodec codec;

  ClaimedPasswordLocalAccounts(JdbcTemplate jdbc, AccountLifecycleCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  record Owned(String kind, String account, Long tenant, String username, String email,
      String version, String consultantVersion, ObjectNode state) {}

  Owned lock(String account) {
    if (!UUID.fromString(account).toString().equals(account)) throw unavailable();
    var rows = codec.read("{}", ObjectNode.class);
    for (String table : List.of("user", "consultant", "admin")) {
      String id = table + "_id";
      var found = jdbc.query("select * from " + table + " where " + id + "=? for update",
          (rs, index) -> row(rs, table, id), account);
      if (found.size() > 1) throw unavailable();
      if (found.isEmpty()) rows.putNull(table); else rows.set(table, found.getFirst());
    }
    String version = codec.claimedPasswordLocalAccountVersion(rows);
    var user = rows.path("user");
    var consultant = rows.path("consultant");
    var admin = rows.path("admin");
    String kind;
    com.fasterxml.jackson.databind.JsonNode primary;
    if (!admin.isNull()) {
      kind = switch (admin.path("type").textValue()) {
        case "SUPER" -> "PLATFORM_ADMIN";
        case "TENANT" -> "TENANT_ADMIN";
        case "AGENCY" -> "AGENCY_ADMIN";
        default -> throw unavailable();
      };
      primary = admin;
    } else if (!consultant.isNull()) { kind = "CONSULTANT"; primary = consultant; }
    else { kind = "USER"; primary = user; }
    for (var candidate : List.of(user, consultant, admin)) {
      if (candidate.isNull()) continue;
      if ((!candidate.path("deleteDate").isMissingNode() && !candidate.path("deleteDate").isNull())
          || (candidate.hasNonNull("lifecycle") && !"ACTIVE".equals(candidate.path("lifecycle").textValue()))
          || candidate.path("managedPaused").asBoolean(false)
          || !primary.path("username").equals(candidate.path("username"))
          || !primary.path("email").equals(candidate.path("email"))) throw unavailable();
    }
    Long tenant = primary.path("tenantId").isNull() ? null : primary.path("tenantId").longValue();
    if (!"PLATFORM_ADMIN".equals(kind) && tenant == null) throw unavailable();
    String username = canonicalUsername(primary.path("username").textValue());
    String consultantVersion = null;
    if (!consultant.isNull()) {
      var only = codec.read("{}", ObjectNode.class);
      only.putNull("user"); only.set("consultant", consultant); only.putNull("admin");
      consultantVersion = codec.claimedPasswordLocalAccountVersion(only);
    }
    var state = codec.read("{}", ObjectNode.class);
    state.put("primaryKind", kind).put("primaryVersion", version);
    if (consultantVersion == null) state.putNull("consultantVersion");
    else state.put("consultantVersion", consultantVersion);
    return new Owned(kind, account, tenant, username, primary.path("email").textValue(),
        version, consultantVersion, state);
  }

  private ObjectNode row(ResultSet rs, String table, String id) throws SQLException {
    var value = codec.read("{}", ObjectNode.class);
    value.put("accountId", rs.getString(id));
    long tenant = rs.getLong("tenant_id");
    if (rs.wasNull()) value.putNull("tenantId"); else value.put("tenantId", tenant);
    value.put("username", rs.getString("username")).put("email", rs.getString("email"));
    for (String field : List.of("create", "update")) {
      var date = rs.getTimestamp(field + "_date");
      if (date == null) throw unavailable();
      value.put(field + "Date", date.toLocalDateTime().toString());
    }
    if ("admin".equals(table)) value.put("type", rs.getString("type"));
    else {
      var date = rs.getTimestamp("delete_date");
      if (date == null) value.putNull("deleteDate"); else value.put("deleteDate", date.toLocalDateTime().toString());
      String lifecycle = rs.getString("deletion_lifecycle_state");
      if (lifecycle == null) value.putNull("lifecycle"); else value.put("lifecycle", lifecycle);
    }
    if (!"user".equals(table)) {
      boolean paused = rs.getBoolean("managed_paused");
      if (rs.wasNull()) throw unavailable();
      value.put("managedPaused", paused);
    }
    if ("consultant".equals(table)) {
      boolean required = rs.getBoolean("password_change_required");
      if (rs.wasNull()) throw unavailable();
      value.put("passwordChangeRequired", required);
    }
    return value;
  }

  static String canonicalUsername(String value) {
    if (value == null || value.isBlank() || value.length() > 255) throw unavailable();
    var transcoder = new UsernameTranscoder();
    String encoded = transcoder.encodeUsername(value);
    if (!encoded.equals(transcoder.encodeUsername(transcoder.decodeUsername(encoded)))) throw unavailable();
    return encoded;
  }

  private static ScopedIdentityCommandRequiredException unavailable() {
    return new ScopedIdentityCommandRequiredException();
  }
}
