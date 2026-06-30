package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogEntry;
import de.caritas.cob.userservice.api.service.SupervisorLogsService.SupervisorLogsResult;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private SupervisorLogsService service;

  @AfterEach
  void cleanTenantContext() {
    TenantContext.clear();
  }

  // ─── Happy path ───────────────────────────────────────────────────────────

  @Test
  void listSupervisorLogs_Should_ReturnResult_When_CalledWithValidParams() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(5L);
    stubJdbc(3L, List.of(buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(3L);
    assertThat(result.getPage()).isEqualTo(1);
    assertThat(result.getPerPage()).isEqualTo(10);
    assertThat(result.getData()).hasSize(1);
  }

  @Test
  void listSupervisorLogs_Should_ReturnMultipleEntries() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(2L);
    stubJdbc(4L, List.of(buildEntry(), buildEntry(), buildEntry(), buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 20);

    assertThat(result.getTotal()).isEqualTo(4L);
    assertThat(result.getData()).hasSize(4);
  }

  @Test
  void listSupervisorLogs_Should_UseZeroTotal_When_JdbcReturnsNull() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
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
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 9999);

    assertThat(result.getPerPage()).isEqualTo(200);
  }

  @Test
  void listSupervisorLogs_Should_ClampPerPageToMin1_When_ZeroGiven() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 0);

    assertThat(result.getPerPage()).isEqualTo(1);
  }

  @Test
  void listSupervisorLogs_Should_ClampPageToMin1_When_NegativePageGiven() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(-3, 10);

    assertThat(result.getPage()).isEqualTo(1);
  }

  // ─── resolveEffectiveTenantId — JWT paths ─────────────────────────────────

  @Test
  void listSupervisorLogs_Should_UseJwtNumericTenantId_When_TokenContainsNumericTenantId() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":7}"));
    stubJdbc(1L, List.of(buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(1L);
  }

  @Test
  void listSupervisorLogs_Should_UseJwtStringTenantId_When_TokenContainsStringTenantId() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":\"3\"}"));
    stubJdbc(2L, List.of(buildEntry(), buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(2L);
  }

  @Test
  void listSupervisorLogs_Should_UseNullTenant_When_JwtTenantIdIsZero() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":0}"));
    stubJdbc(10L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(10L);
  }

  @Test
  void listSupervisorLogs_Should_FallbackToTenantContext_When_NoAccessToken() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(9L);
    stubJdbc(2L, List.of(buildEntry(), buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(2L);
  }

  @Test
  void listSupervisorLogs_Should_FallbackToTenantContext_When_AccessTokenIsBlank() {
    when(authenticatedUser.getAccessToken()).thenReturn("   ");
    TenantContext.setCurrentTenant(4L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listSupervisorLogs_Should_FallbackToTenantContext_When_TokenHasFewerThanTwoParts() {
    when(authenticatedUser.getAccessToken()).thenReturn("notavalidjwt");
    TenantContext.setCurrentTenant(6L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listSupervisorLogs_Should_ReturnNullTenant_When_TenantContextIsTechnicalAdmin() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(0L); // isTechnicalOrSuperAdminContext = true
    stubJdbc(15L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isEqualTo(15L);
  }

  @Test
  void listSupervisorLogs_Should_FallbackGracefully_When_TokenPayloadIsMalformedJson() {
    String fakeHeader = Base64.getUrlEncoder().encodeToString("{}".getBytes());
    String fakePayload = Base64.getUrlEncoder().encodeToString("{not-json}".getBytes());
    when(authenticatedUser.getAccessToken()).thenReturn(fakeHeader + "." + fakePayload + ".sig");
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listSupervisorLogs_Should_FallbackGracefully_When_TenantIdIsBlankString() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":\"\"}"));
    TenantContext.setCurrentTenant(2L);
    stubJdbc(0L, List.of());

    SupervisorLogsResult result = service.listSupervisorLogs(1, 10);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listSupervisorLogs_Should_ReturnPagedResult_When_MultiplePages() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(100L, List.of(buildEntry()));

    SupervisorLogsResult result = service.listSupervisorLogs(3, 10);

    assertThat(result.getPage()).isEqualTo(3);
    assertThat(result.getTotal()).isEqualTo(100L);
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

  private String buildToken(String payloadJson) {
    String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"RS256\"}".getBytes());
    String payload = Base64.getUrlEncoder().encodeToString(payloadJson.getBytes());
    return header + "." + payload + ".fakesig";
  }
}
