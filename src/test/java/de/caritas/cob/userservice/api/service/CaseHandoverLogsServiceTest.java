package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogsResult;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseHandoverLogsServiceTest {

  @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  @Mock private AdminScope adminScope;

  @InjectMocks private CaseHandoverLogsService service;

  @BeforeEach
  void tenantWideByDefault() {
    when(adminScope.current()).thenReturn(new AdminScope.Tenant(1L));
  }

  @AfterEach
  void cleanTenantContext() {
    TenantContext.clear();
  }

  @Test
  void listCaseHandoverLogs_Should_NotFilterByAgency_When_AdminIsTenantWide() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(namedParameterJdbcTemplate.queryForObject(
            sqlCaptor.capture(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(2L);
    when(namedParameterJdbcTemplate.query(
            sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    CaseHandoverLogsResult result = service.listCaseHandoverLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(2L);
    assertThat(sqlCaptor.getAllValues())
        .allSatisfy(sql -> assertThat(sql).doesNotContain("agencyIds"));
  }

  /** #200: the extension time outlives the expiry sweep that overwrites the outcome. */
  @Test
  void listCaseHandoverLogs_Should_ShowWhenACoAccessWasExtended() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(namedParameterJdbcTemplate.query(
            sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    service.listCaseHandoverLogs(1, 10);

    assertThat(sqlCaptor.getValue()).contains("chr.extended_at AS extendedAt");
  }

  @Test
  void listCaseHandoverLogs_Should_FilterByAgency_When_AdminIsAgencyScoped() {
    when(adminScope.current()).thenReturn(new AdminScope.Agencies(1L, Set.of(11L)));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    when(namedParameterJdbcTemplate.queryForObject(
            sqlCaptor.capture(), paramsCaptor.capture(), eq(Long.class)))
        .thenReturn(0L);
    when(namedParameterJdbcTemplate.query(
            sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    service.listCaseHandoverLogs(1, 10);

    assertThat(sqlCaptor.getAllValues())
        .allSatisfy(sql -> assertThat(sql).contains("s.agency_id IN (:agencyIds)"));
    assertThat(paramsCaptor.getValue().getValue("agencyIds")).isEqualTo(Set.of(11L));
  }

  @Test
  void listCaseHandoverLogs_Should_ReturnNothing_When_AgencyScopeIsEmpty() {
    // Fail closed: an agency admin assigned to no agency must not fall back to the tenant.
    when(adminScope.current()).thenReturn(new AdminScope.Agencies(1L, Set.of()));

    CaseHandoverLogsResult result = service.listCaseHandoverLogs(1, 10);

    assertThat(result.getTotal()).isZero();
    assertThat(result.getData()).isEmpty();
    verifyNoInteractions(namedParameterJdbcTemplate);
  }

  @Test
  void listCaseHandoverLogs_Should_ClampPaginationBounds() {
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(0L);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    CaseHandoverLogsResult result = service.listCaseHandoverLogs(-3, 5000);

    assertThat(result.getPage()).isEqualTo(1);
    assertThat(result.getPerPage()).isEqualTo(200);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> reachAndTenant() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.arguments(new AdminScope.Platform(), null),
        org.junit.jupiter.params.provider.Arguments.arguments(new AdminScope.Tenant(4L), 4L),
        org.junit.jupiter.params.provider.Arguments.arguments(
            new AdminScope.Agencies(4L, Set.of(11L)), 4L));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("reachAndTenant")
  void listCaseHandoverLogs_Should_BindTheTenantOfTheCallersReach(
      AdminScope.Reach reach, Long tenantId) {
    when(adminScope.current()).thenReturn(reach);
    ArgumentCaptor<SqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    when(namedParameterJdbcTemplate.queryForObject(
            any(String.class), paramsCaptor.capture(), eq(Long.class)))
        .thenReturn(0L);

    service.listCaseHandoverLogs(1, 10);

    assertThat(paramsCaptor.getValue().getValue("tenantId")).isEqualTo(tenantId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void listCaseHandoverLogs_Should_ShowANeutralLabel_When_ARecordCarriesARetiredReason()
      throws Exception {
    ArgumentCaptor<RowMapper<CaseHandoverLogsService.CaseHandoverLogEntry>> mapperCaptor =
        ArgumentCaptor.forClass(RowMapper.class);
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(1L);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), mapperCaptor.capture()))
        .thenReturn(List.of());
    service.listCaseHandoverLogs(1, 10);
    java.sql.ResultSet row = org.mockito.Mockito.mock(java.sql.ResultSet.class);
    when(row.getString("reasonCode")).thenReturn("COUNSELLOR_IS_ILL");
    when(row.getString("reasonLabel")).thenReturn("Counsellor is ill");

    var entry = mapperCaptor.getValue().mapRow(row, 0);

    assertThat(entry.getReasonCode()).isEqualTo("COUNSELLOR_IS_ILL");
    assertThat(entry.getReasonLabel()).isEqualTo("Unplanned absence");
  }
}
