package de.caritas.cob.userservice.api.service.identity;

import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/** Complete owning local relationship snapshots are data, never a native authority factory. */
final class ConsultantProfileSqlSnapshot {
  private ConsultantProfileSqlSnapshot() {}

  static String capture(JdbcTemplate jdbc, String id, boolean lock, Function<Object, String> encoder) {
    var tables = new java.util.TreeMap<String, List<String>>();
    for (String table :
        List.of(
            "admin",
            "consultant",
            "language",
            "consultant_topic",
            "consultant_agency",
            "admin_agency")) {
      String column = table.startsWith("admin") ? "admin_id" : "consultant_id";
      var rows =
          jdbc.queryForList(
              "select * from " + table + " where " + column + "=?" + (lock ? " for update" : ""),
              id);
      var encoded = new java.util.ArrayList<String>();
      for (var row : rows) {
        var normalized = new java.util.TreeMap<String, Object>();
        row.forEach(
            (field, value) ->
                normalized.put(
                    field,
                    value instanceof java.sql.Timestamp t
                        ? t.toInstant().toString()
                        : value instanceof byte[] bytes
                            ? java.util.HexFormat.of().formatHex(bytes)
                            : value));
        encoded.add(encoder.apply(normalized));
      }
      java.util.Collections.sort(encoded);
      tables.put(table, List.copyOf(encoded));
    }
    return encoder.apply(tables);
  }
}
