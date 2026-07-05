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

@Service
@RequiredArgsConstructor
public class CaseHandoverLogsService {

  private final @NonNull NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CaseHandoverLogsResult listCaseHandoverLogs(int page, int perPage) {
    int safePerPage = Math.min(Math.max(perPage, 1), 200);
    int safePage = Math.max(page, 1);
    int offset = (safePage - 1) * safePerPage;
    Long tenantId = resolveEffectiveTenantId();

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", safePerPage)
            .addValue("offset", offset);

    Long total =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(*)\n"
                + "FROM case_handover_request chr\n"
                + "JOIN session s ON s.id = chr.session_id\n"
                + "WHERE (:tenantId IS NULL OR s.tenant_id = :tenantId OR (:tenantId = 1 AND s.tenant_id IS NULL))",
            params,
            Long.class);

    List<CaseHandoverLogEntry> data =
        namedParameterJdbcTemplate.query(
            "SELECT\n"
                + "  chr.id AS requestId,\n"
                + "  chr.session_id AS sessionId,\n"
                + "  chr.status AS status,\n"
                + "  chr.audit_outcome AS auditOutcome,\n"
                + "  chr.reason_code AS reasonCode,\n"
                + "  chr.reason_label AS reasonLabel,\n"
                + "  chr.explanation AS explanation,\n"
                + "  chr.client_consent_required AS clientConsentRequired,\n"
                + "  chr.policy_authority AS policyAuthority,\n"
                + "  chr.created_at AS createdAt,\n"
                + "  chr.resolved_at AS resolvedAt,\n"
                + "  chr.requester_consultant_id AS requesterConsultantId,\n"
                + "  req.username AS requesterUsername,\n"
                + "  COALESCE(NULLIF(req.display_name, ''), CONCAT(req.first_name, ' ', req.last_name)) AS requesterName,\n"
                + "  chr.previous_consultant_id AS previousConsultantId,\n"
                + "  prev.username AS previousUsername,\n"
                + "  COALESCE(NULLIF(prev.display_name, ''), CONCAT(prev.first_name, ' ', prev.last_name)) AS previousName\n"
                + "FROM case_handover_request chr\n"
                + "JOIN session s ON s.id = chr.session_id\n"
                + "JOIN consultant req ON req.consultant_id = chr.requester_consultant_id\n"
                + "LEFT JOIN consultant prev ON prev.consultant_id = chr.previous_consultant_id\n"
                + "WHERE (:tenantId IS NULL OR s.tenant_id = :tenantId OR (:tenantId = 1 AND s.tenant_id IS NULL))\n"
                + "ORDER BY chr.created_at DESC\n"
                + "LIMIT :limit OFFSET :offset",
            params,
            new CaseHandoverLogEntryRowMapper());

    return CaseHandoverLogsResult.builder()
        .data(data)
        .total(total != null ? total : 0L)
        .page(safePage)
        .perPage(safePerPage)
        .build();
  }

  private static class CaseHandoverLogEntryRowMapper implements RowMapper<CaseHandoverLogEntry> {
    @Override
    public CaseHandoverLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
      return CaseHandoverLogEntry.builder()
          .requestId(rs.getLong("requestId"))
          .sessionId(rs.getLong("sessionId"))
          .status(rs.getString("status"))
          .auditOutcome(rs.getString("auditOutcome"))
          .reasonCode(rs.getString("reasonCode"))
          .reasonLabel(rs.getString("reasonLabel"))
          .explanation(rs.getString("explanation"))
          .clientConsentRequired(rs.getBoolean("clientConsentRequired"))
          .policyAuthority(rs.getString("policyAuthority"))
          .createdAt(rs.getObject("createdAt", LocalDateTime.class))
          .resolvedAt(rs.getObject("resolvedAt", LocalDateTime.class))
          .requesterConsultantId(rs.getString("requesterConsultantId"))
          .requesterUsername(rs.getString("requesterUsername"))
          .requesterName(rs.getString("requesterName"))
          .previousConsultantId(rs.getString("previousConsultantId"))
          .previousUsername(rs.getString("previousUsername"))
          .previousName(rs.getString("previousName"))
          .build();
    }
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
      JsonNode tenantIdNode = objectMapper.readTree(payload).get("tenantId");
      if (tenantIdNode == null || tenantIdNode.isNull()) {
        return null;
      }
      if (tenantIdNode.isNumber()) {
        return tenantIdNode.asLong();
      }
      if (tenantIdNode.isTextual() && !tenantIdNode.asText().isBlank()) {
        return Long.parseLong(tenantIdNode.asText());
      }
      return null;
    } catch (Exception exception) {
      return null;
    }
  }

  @Data
  @Builder
  public static class CaseHandoverLogEntry {
    private Long requestId;
    private Long sessionId;
    private String status;
    private String auditOutcome;
    private String reasonCode;
    private String reasonLabel;
    private String explanation;
    private boolean clientConsentRequired;
    private String policyAuthority;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private String requesterConsultantId;
    private String requesterUsername;
    private String requesterName;
    private String previousConsultantId;
    private String previousUsername;
    private String previousName;
  }

  @Data
  @Builder
  public static class CaseHandoverLogsResult {
    private List<CaseHandoverLogEntry> data;
    private long total;
    private int page;
    private int perPage;
  }
}
