package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupervisorLogsService {

  private final @NonNull NamedParameterJdbcTemplate namedParameterJdbcTemplate;

  public SupervisorLogsResult listSupervisorLogs(int page, int perPage) {
    final int safePerPage = Math.min(Math.max(perPage, 1), 200);
    final int safePage = Math.max(page, 1);
    final int offset = (safePage - 1) * safePerPage;

    final Long tenantId = TenantContext.isTechnicalOrSuperAdminContext() ? null : TenantContext.getCurrentTenant();

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", safePerPage)
            .addValue("offset", offset);

    // Total count for pagination: added events + removed events (only where removed_date exists).
    Long total =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT\n"
                + "  (\n"
                + "    SELECT COUNT(*)\n"
                + "    FROM session_supervisor ss\n"
                + "    JOIN session s ON s.id = ss.session_id\n"
                + "    WHERE (:tenantId IS NULL OR s.tenant_id = :tenantId)\n"
                + "  )\n"
                + "  +\n"
                + "  (\n"
                + "    SELECT COUNT(*)\n"
                + "    FROM session_supervisor ss\n"
                + "    JOIN session s ON s.id = ss.session_id\n"
                + "    WHERE ss.removed_date IS NOT NULL\n"
                + "      AND (:tenantId IS NULL OR s.tenant_id = :tenantId)\n"
                + "  ) AS total",
            params,
            Long.class);

    List<SupervisorLogEntry> data =
        namedParameterJdbcTemplate.query(
            "SELECT e.*\n"
                + "FROM (\n"
                + "  SELECT\n"
                + "    ss.id AS relationId,\n"
                + "    ss.session_id AS sessionId,\n"
                + "    'ADDED' AS action,\n"
                + "    ss.added_date AS eventDate,\n"
                + "    ss.supervisor_consultant_id AS supervisorConsultantId,\n"
                + "    sup.username AS supervisorUsername,\n"
                + "    COALESCE(NULLIF(sup.display_name, ''), CONCAT(sup.first_name, ' ', sup.last_name)) AS supervisorName,\n"
                + "    ss.added_by_consultant_id AS actorConsultantId,\n"
                + "    act.username AS actorUsername,\n"
                + "    COALESCE(NULLIF(act.display_name, ''), CONCAT(act.first_name, ' ', act.last_name)) AS actorName,\n"
                + "    ss.notes AS notes\n"
                + "  FROM session_supervisor ss\n"
                + "  JOIN session s ON s.id = ss.session_id\n"
                + "  JOIN consultant sup ON sup.consultant_id = ss.supervisor_consultant_id\n"
                + "  JOIN consultant act ON act.consultant_id = ss.added_by_consultant_id\n"
                + "  WHERE (:tenantId IS NULL OR s.tenant_id = :tenantId)\n"
                + "\n"
                + "  UNION ALL\n"
                + "\n"
                + "  SELECT\n"
                + "    ss.id AS relationId,\n"
                + "    ss.session_id AS sessionId,\n"
                + "    'REMOVED' AS action,\n"
                + "    ss.removed_date AS eventDate,\n"
                + "    ss.supervisor_consultant_id AS supervisorConsultantId,\n"
                + "    sup.username AS supervisorUsername,\n"
                + "    COALESCE(NULLIF(sup.display_name, ''), CONCAT(sup.first_name, ' ', sup.last_name)) AS supervisorName,\n"
                + "    ss.added_by_consultant_id AS actorConsultantId,\n"
                + "    act.username AS actorUsername,\n"
                + "    COALESCE(NULLIF(act.display_name, ''), CONCAT(act.first_name, ' ', act.last_name)) AS actorName,\n"
                + "    ss.notes AS notes\n"
                + "  FROM session_supervisor ss\n"
                + "  JOIN session s ON s.id = ss.session_id\n"
                + "  JOIN consultant sup ON sup.consultant_id = ss.supervisor_consultant_id\n"
                + "  JOIN consultant act ON act.consultant_id = ss.added_by_consultant_id\n"
                + "  WHERE ss.removed_date IS NOT NULL\n"
                + "    AND (:tenantId IS NULL OR s.tenant_id = :tenantId)\n"
                + ") e\n"
                + "ORDER BY e.eventDate DESC\n"
                + "LIMIT :limit OFFSET :offset",
            params,
            new SupervisorLogEntryRowMapper());

    return SupervisorLogsResult.builder()
        .data(data)
        .total(total != null ? total : 0L)
        .page(safePage)
        .perPage(safePerPage)
        .build();
  }

  private static class SupervisorLogEntryRowMapper implements RowMapper<SupervisorLogEntry> {
    @Override
    public SupervisorLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
      return SupervisorLogEntry.builder()
          .relationId(rs.getLong("relationId"))
          .sessionId(rs.getLong("sessionId"))
          .action(rs.getString("action"))
          .eventDate(rs.getObject("eventDate", LocalDateTime.class))
          .supervisorConsultantId(rs.getString("supervisorConsultantId"))
          .supervisorUsername(rs.getString("supervisorUsername"))
          .supervisorName(rs.getString("supervisorName"))
          .actorConsultantId(rs.getString("actorConsultantId"))
          .actorUsername(rs.getString("actorUsername"))
          .actorName(rs.getString("actorName"))
          .notes(rs.getString("notes"))
          .build();
    }
  }

  @Data
  @Builder
  public static class SupervisorLogEntry {
    private Long relationId;
    private Long sessionId;
    private String action; // ADDED | REMOVED
    private LocalDateTime eventDate;
    private String supervisorConsultantId;
    private String supervisorUsername;
    private String supervisorName;
    private String actorConsultantId;
    private String actorUsername;
    private String actorName;
    private String notes;
  }

  @Data
  @Builder
  public static class SupervisorLogsResult {
    private List<SupervisorLogEntry> data;
    private long total;
    private int page;
    private int perPage;
  }
}


