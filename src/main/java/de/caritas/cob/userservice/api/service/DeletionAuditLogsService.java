package de.caritas.cob.userservice.api.service;

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

/**
 * Read model for the deletion log. Not tenant-filtered on purpose: the endpoint is restricted to
 * platform admins and the technical account (see SecurityConfig), exactly like the inactive-account
 * audit log, and a tenant deletion has no tenant of its own to filter by.
 */
@Service
@RequiredArgsConstructor
public class DeletionAuditLogsService {

  private final @NonNull NamedParameterJdbcTemplate namedParameterJdbcTemplate;

  public DeletionAuditLogsResult listAuditLogs(
      int page, int perPage, String targetType, String outcome, String targetId) {
    final int safePerPage = Math.min(Math.max(perPage, 1), 200);
    final int safePage = Math.max(page, 1);

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("targetType", normalize(targetType))
            .addValue("outcome", normalize(outcome))
            .addValue("targetId", likeLiteral(normalize(targetId)))
            .addValue("limit", safePerPage)
            .addValue("offset", (long) (safePage - 1) * safePerPage);

    final String filtersSql =
        " WHERE (:targetType IS NULL OR UPPER(l.target_type) = :targetType)"
            + " AND (:outcome IS NULL OR UPPER(l.outcome) = :outcome)"
            + " AND (:targetId IS NULL OR UPPER(l.target_id) LIKE CONCAT('%', :targetId, '%') ESCAPE '!')";

    Long total =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deletion_audit_log l" + filtersSql, params, Long.class);

    List<DeletionAuditLogEntry> data =
        namedParameterJdbcTemplate.query(
            "SELECT l.id AS id, l.actor_id AS actorId, l.actor_role AS actorRole, "
                + "l.target_type AS targetType, l.target_id AS targetId, "
                + "l.tenant_id AS tenantId, l.outcome AS outcome, l.create_date AS createDate "
                + "FROM deletion_audit_log l"
                + filtersSql
                + " ORDER BY l.create_date DESC, l.id DESC"
                + " LIMIT :limit OFFSET :offset",
            params,
            new DeletionAuditLogEntryRowMapper());

    return DeletionAuditLogsResult.builder()
        .data(data)
        .total(total != null ? total : 0L)
        .page(safePage)
        .perPage(safePerPage)
        .build();
  }

  /** A typed % or _ is part of the id searched for, not a wildcard. */
  private static String likeLiteral(String value) {
    if (value == null) {
      return null;
    }
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private String normalize(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim().toUpperCase();
  }

  private static class DeletionAuditLogEntryRowMapper implements RowMapper<DeletionAuditLogEntry> {
    @Override
    public DeletionAuditLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
      return DeletionAuditLogEntry.builder()
          .id(rs.getLong("id"))
          .actorId(rs.getString("actorId"))
          .actorRole(rs.getString("actorRole"))
          .targetType(rs.getString("targetType"))
          .targetId(rs.getString("targetId"))
          .tenantId(rs.getObject("tenantId", Long.class))
          .outcome(rs.getString("outcome"))
          .createDate(rs.getObject("createDate", LocalDateTime.class))
          .build();
    }
  }

  @Data
  @Builder
  public static class DeletionAuditLogEntry {
    private Long id;
    private String actorId;
    private String actorRole;
    private String targetType;
    private String targetId;
    private Long tenantId;
    private String outcome;
    private LocalDateTime createDate;
  }

  @Data
  @Builder
  public static class DeletionAuditLogsResult {
    private List<DeletionAuditLogEntry> data;
    private long total;
    private int page;
    private int perPage;
  }
}
