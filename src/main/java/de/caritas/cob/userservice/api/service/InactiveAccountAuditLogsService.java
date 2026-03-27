package de.caritas.cob.userservice.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Read model service for Security-06 inactivity audit logs. */
@Service
@RequiredArgsConstructor
public class InactiveAccountAuditLogsService {

  private final @NonNull NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public InactiveAccountAuditLogsResult listAuditLogs(
      int page, int perPage, String accountRole, String accountId) {
    final int safePerPage = Math.min(Math.max(perPage, 1), 200);
    final int safePage = Math.max(page, 1);
    final int offset = (safePage - 1) * safePerPage;

    final Long tenantId = resolveEffectiveTenantId();

    final String normalizedRole = normalize(accountRole);
    final String normalizedAccountId = normalize(accountId);

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("role", normalizedRole)
            .addValue("accountId", normalizedAccountId)
            .addValue("limit", safePerPage)
            .addValue("offset", offset);

    final String filtersSql =
        " WHERE (:tenantId IS NULL OR l.tenant_id = :tenantId OR (:tenantId = 1 AND l.tenant_id IS NULL))"
            + " AND (:role IS NULL OR UPPER(l.account_role) = :role)"
            + " AND (:accountId IS NULL OR UPPER(l.account_id) LIKE CONCAT('%', :accountId, '%'))";

    Long total =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM inactive_account_notification_audit_log l" + filtersSql,
            params,
            Long.class);

    List<InactiveAccountAuditLogEntry> data =
        namedParameterJdbcTemplate.query(
            "SELECT "
                + "l.id AS id, "
                + "l.account_role AS accountRole, "
                + "l.account_id AS accountId, "
                + "l.account_tenant_id AS accountTenantId, "
                + "l.last_activity_at AS lastActivityAt, "
                + "l.threshold_days AS thresholdDays, "
                + "l.recipient_admin_id AS recipientAdminId, "
                + "l.recipient_email AS recipientEmail, "
                + "l.email_dispatched AS emailDispatched, "
                + "l.create_date AS createDate "
                + "FROM inactive_account_notification_audit_log l"
                + filtersSql
                + " ORDER BY l.create_date DESC, l.id DESC"
                + " LIMIT :limit OFFSET :offset",
            params,
            new InactiveAccountAuditLogEntryRowMapper());

    return InactiveAccountAuditLogsResult.builder()
        .data(data)
        .total(total != null ? total : 0L)
        .page(safePage)
        .perPage(safePerPage)
        .build();
  }

  private String normalize(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim().toUpperCase();
  }

  private Long resolveEffectiveTenantId() {
    Long tokenTenantId = getTenantIdFromAccessToken();
    if (tokenTenantId != null) {
      return tokenTenantId == 0L ? null : tokenTenantId;
    }
    return TenantContext.isTechnicalOrSuperAdminContext() ? null : TenantContext.getCurrentTenant();
  }

  private Long getTenantIdFromAccessToken() {
    try {
      String accessToken = authenticatedUser.getAccessToken();
      if (accessToken == null || accessToken.isBlank()) {
        return null;
      }
      String[] tokenParts = accessToken.split("\\.");
      if (tokenParts.length < 2) {
        return null;
      }
      String payload =
          new String(Base64.getUrlDecoder().decode(tokenParts[1]), StandardCharsets.UTF_8);
      JsonNode payloadNode = objectMapper.readTree(payload);
      JsonNode tenantIdNode = payloadNode.get("tenantId");
      if (tenantIdNode == null || tenantIdNode.isNull()) {
        return null;
      }
      if (tenantIdNode.isNumber()) {
        return tenantIdNode.asLong();
      }
      if (tenantIdNode.isTextual()) {
        String tenantIdText = tenantIdNode.asText();
        if (tenantIdText.isBlank()) {
          return null;
        }
        return Long.parseLong(tenantIdText);
      }
      return null;
    } catch (Exception exception) {
      return null;
    }
  }

  private static class InactiveAccountAuditLogEntryRowMapper
      implements RowMapper<InactiveAccountAuditLogEntry> {
    @Override
    public InactiveAccountAuditLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
      return InactiveAccountAuditLogEntry.builder()
          .id(rs.getLong("id"))
          .accountRole(rs.getString("accountRole"))
          .accountId(rs.getString("accountId"))
          .accountTenantId(rs.getObject("accountTenantId", Long.class))
          .lastActivityAt(rs.getObject("lastActivityAt", LocalDateTime.class))
          .thresholdDays(rs.getInt("thresholdDays"))
          .recipientAdminId(rs.getString("recipientAdminId"))
          .recipientEmail(rs.getString("recipientEmail"))
          .emailDispatched(rs.getBoolean("emailDispatched"))
          .createDate(rs.getObject("createDate", LocalDateTime.class))
          .build();
    }
  }

  @Data
  @Builder
  public static class InactiveAccountAuditLogEntry {
    private Long id;
    private String accountRole;
    private String accountId;
    private Long accountTenantId;
    private LocalDateTime lastActivityAt;
    private Integer thresholdDays;
    private String recipientAdminId;
    private String recipientEmail;
    private Boolean emailDispatched;
    private LocalDateTime createDate;
  }

  @Data
  @Builder
  public static class InactiveAccountAuditLogsResult {
    private List<InactiveAccountAuditLogEntry> data;
    private long total;
    private int page;
    private int perPage;
  }
}
