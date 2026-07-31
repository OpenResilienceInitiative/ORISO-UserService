package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.CaseHandoverLogsService.CaseHandoverLogsResult;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
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
  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private AdminAuditAgencyScope adminAuditAgencyScope;

  @InjectMocks private CaseHandoverLogsService service;

  @BeforeEach
  void tenantWideByDefault() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    when(adminAuditAgencyScope.resolveAgencyIds()).thenReturn(Optional.empty());
    TenantContext.setCurrentTenant(5L);
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

  @Test
  void listCaseHandoverLogs_Should_FilterByAgency_When_AdminIsAgencyScoped() {
    when(adminAuditAgencyScope.resolveAgencyIds()).thenReturn(Optional.of(Set.of(11L)));
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
    when(adminAuditAgencyScope.resolveAgencyIds()).thenReturn(Optional.of(Set.of()));

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
}
