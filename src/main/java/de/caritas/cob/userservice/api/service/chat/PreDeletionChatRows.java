package de.caritas.cob.userservice.api.service.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private, transient related-row comparison data. It cannot create an ACK or select a target. */
final class PreDeletionChatRows {
  private PreDeletionChatRows() {}

  static Map<String, List<List<String>>> capture(JdbcTemplate jdbc, String account) {
    String series = "select series_id from group_chat_participant where consultant_id=?";
    String sessions =
        "select id from session where consultant_id=? or id in (select chat_id from group_chat_participant where series_id in ("
            + series
            + ")) or matrix_room_id in (select matrix_room_id from chat where id in ("
            + series
            + "))";
    String participation =
        "consultant_id=? or series_id in (" + series + ") or chat_id in (" + sessions + ")";
    var result = new LinkedHashMap<String, List<List<String>>>();
    result.put(
        "chat",
        rows(
            jdbc,
            "select * from chat where id in (" + series + ") order by id for update",
            account));
    result.put(
        "participant",
        rows(
            jdbc,
            "select * from group_chat_participant where "
                + participation
                + " order by id for update",
            account));
    result.put(
        "session",
        rows(
            jdbc,
            "select * from session where id in (" + sessions + ") order by id for update",
            account));
    result.put(
        "supervision",
        rows(
            jdbc,
            "select * from session_supervisor where session_id in ("
                + sessions
                + ") order by id for update",
            account));
    result.put(
        "consultant",
        rows(
            jdbc,
            "select * from consultant where consultant_id=? or consultant_id in (select consultant_id from group_chat_participant where "
                + participation
                + ") or consultant_id in (select supervisor_consultant_id from session_supervisor where session_id in ("
                + sessions
                + ")) order by consultant_id for update",
            account));
    result.put(
        "agency",
        rows(jdbc,
            "select * from consultant_agency where delete_date is null and (consultant_id=? or agency_id in (select agency_id from consultant_agency where consultant_id=? and delete_date is null)) order by id for update",
            account));
    return Collections.unmodifiableMap(result);
  }

  private static List<List<String>> rows(JdbcTemplate jdbc, String sql, String account) {
    Object[] parameters =
        Collections.nCopies((int) sql.chars().filter(c -> c == '?').count(), account).toArray();
    return jdbc.query(
        sql,
        rs -> {
          var result = new ArrayList<List<String>>();
          int columns = rs.getMetaData().getColumnCount();
          var header = new ArrayList<String>();
          for (int column = 1; column <= columns; column++) {
            header.add(rs.getMetaData().getColumnLabel(column));
            header.add(Integer.toString(rs.getMetaData().getColumnType(column)));
            header.add(rs.getMetaData().getColumnTypeName(column));
          }
          result.add(List.copyOf(header));
          while (rs.next()) {
            var row = new ArrayList<String>();
            for (int column = 1; column <= columns; column++) row.add(rs.getString(column));
            result.add(Collections.unmodifiableList(row));
          }
          return Collections.unmodifiableList(result);
        },
        parameters);
  }
}
