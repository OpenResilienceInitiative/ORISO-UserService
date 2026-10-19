package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogEntry;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogsResult;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
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
class SupervisorLogsServiceTest {

  @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  @Mock private AdminScope adminScope;

  @InjectMocks private SupervisorLogsService service;

  @BeforeEach
  void tenantWideByDefault() {
    when(adminScope.current()).thenReturn(new AdminScope.Tenant(1L));
  }

  @AfterEach
  void cleanTenantContext() {
    TenantContext.clear();
  }

  // ─── Happy path ───────────────────────────────────────────────────────────

  @Test
  void listSupervisorLogs_Should_ReturnResult_When_CalledWithValidParams() {
    stubJdbc(3L, List.of(buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(3L);
    assertThat(result.getPage()).isEqualTo(1);
    assertThat(result.getPerPage()).isEqualTo(10);
    assertThat(result.getData()).hasSize(1);
  }

  @Test
  void listSupervisorLogs_Should_ReturnMultipleEntries() {
    stubJdbc(4L, List.of(buildEntry(), buildEntry(), buildEntry(), buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 20);

    assertThat(result.getTotal()).isEqualTo(4L);
    assertThat(result.getData()).hasSize(4);
  }

  @Test
  void listSupervisorLogs_Should_UseZeroTotal_When_JdbcReturnsNull() {
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(null);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isZero();
  }

  // ─── Pagination clamping ──────────────────────────────────────────────────

  @Test
  void listSupervisorLogs_Should_ClampPerPageToMax200_When_LargeValueGiven() {
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 9999);

    assertThat(result.getPerPage()).isEqualTo(200);
  }

  @Test
  void listSupervisorLogs_Should_ClampPerPageToMin1_When_ZeroGiven() {
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 0);

    assertThat(result.getPerPage()).isEqualTo(1);
  }

  @Test
  void listSupervisorLogs_Should_ClampPageToMin1_When_NegativePageGiven() {
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(-3, 10);

    assertThat(result.getPage()).isEqualTo(1);
  }

  // ─── resolveEffectiveTenantId — JWT paths ─────────────────────────────────

  @Test
  void listSupervisorLogs_Should_ReturnPagedResult_When_MultiplePages() {
    stubJdbc(100L, List.of(buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(3, 10);

    assertThat(result.getPage()).isEqualTo(3);
    assertThat(result.getTotal()).isEqualTo(100L);
  }

  // ─── tenantId bound into SQL params ───────────────────────────────────────

  // ─── agency scope (Beratungsstellen-Admins) ───────────────────────────────

  @Test
  void listSupervisorLogs_Should_FilterByAgency_When_AdminIsAgencyScoped() {
    when(adminScope.current()).thenReturn(new AdminScope.Agencies(1L, Set.of(7L, 9L)));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<SqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    when(namedParameterJdbcTemplate.queryForObject(
            sqlCaptor.capture(), paramsCaptor.capture(), eq(Long.class)))
        .thenReturn(0L);
    when(namedParameterJdbcTemplate.query(
            sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    service.listSupervisorLogs(1, 10);

    assertThat(sqlCaptor.getAllValues())
        .allSatisfy(sql -> assertThat(sql).contains("s.agency_id IN (:agencyIds)"));
    assertThat(paramsCaptor.getValue().getValue("agencyIds")).isEqualTo(Set.of(7L, 9L));
  }

  @Test
  void listSupervisorLogs_Should_NotFilterByAgency_When_AdminIsTenantWide() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(namedParameterJdbcTemplate.queryForObject(
            sqlCaptor.capture(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(0L);
    when(namedParameterJdbcTemplate.query(
            sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    service.listSupervisorLogs(1, 10);

    assertThat(sqlCaptor.getAllValues())
        .allSatisfy(sql -> assertThat(sql).doesNotContain("agencyIds"));
  }

  @Test
  void listSupervisorLogs_Should_ReturnNothing_When_AgencyScopeIsEmpty() {
    // Fail closed: an agency admin assigned to no agency must not fall back to the tenant.
    when(adminScope.current()).thenReturn(new AdminScope.Agencies(1L, Set.of()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isZero();
    assertThat(result.getData()).isEmpty();
    verifyNoInteractions(namedParameterJdbcTemplate);
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private void stubJdbc(long total, List<SupervisorLogEntry> data) {
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(total);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(data);
  }

  private SupervisorLogEntry buildEntry() {
    return SupervisorLogEntry.builder()
        .relationId(1L)
        .sessionId(100L)
        .action("ADDED")
        .eventDate(LocalDateTime.now())
        .supervisorConsultantId("sup-1")
        .supervisorUsername("supervisor")
        .supervisorName("John Supervisor")
        .actorConsultantId("act-1")
        .actorUsername("actor")
        .actorName("Jane Actor")
        .notes("some notes")
        .build();
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
  void listSupervisorLogs_Should_BindTheTenantOfTheCallersReach(
      AdminScope.Reach reach, Long tenantId) {
    when(adminScope.current()).thenReturn(reach);
    ArgumentCaptor<SqlParameterSource> paramsCaptor =
        ArgumentCaptor.forClass(SqlParameterSource.class);
    when(namedParameterJdbcTemplate.queryForObject(
            any(String.class), paramsCaptor.capture(), eq(Long.class)))
        .thenReturn(0L);

    service.listSupervisorLogs(1, 10);

    assertThat(paramsCaptor.getValue().getValue("tenantId")).isEqualTo(tenantId);
  }
}
