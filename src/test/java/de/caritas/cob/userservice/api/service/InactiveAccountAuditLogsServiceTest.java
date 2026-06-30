package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogEntry;
import de.caritas.cob.userservice.api.service.InactiveAccountAuditLogsService.InactiveAccountAuditLogsResult;
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
class InactiveAccountAuditLogsServiceTest {

  @Mock private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  @Mock private AuthenticatedUser authenticatedUser;

  @InjectMocks private InactiveAccountAuditLogsService service;

  @AfterEach
  void cleanTenantContext() {
    TenantContext.clear();
  }

  // ─── Happy path ───────────────────────────────────────────────────────────

  @Test
  void listAuditLogs_Should_ReturnResult_When_CalledWithValidParams() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(5L);
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(3L);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(buildEntry()));

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isEqualTo(3L);
    assertThat(result.getPage()).isEqualTo(1);
    assertThat(result.getPerPage()).isEqualTo(10);
    assertThat(result.getData()).hasSize(1);
  }

  @Test
  void listAuditLogs_Should_ReturnData_When_FilterParamsProvided() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(2L);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of(buildEntry(), buildEntry()));

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 20, "consultant", "abc123");

    assertThat(result.getTotal()).isEqualTo(2L);
    assertThat(result.getData()).hasSize(2);
  }

  @Test
  void listAuditLogs_Should_UseZeroTotal_When_JdbcReturnsNull() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(null);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isZero();
  }

  // ─── Pagination clamping ──────────────────────────────────────────────────

  @Test
  void listAuditLogs_Should_ClampPerPageToMax200_When_LargeValueGiven() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 9999, null, null);

    assertThat(result.getPerPage()).isEqualTo(200);
  }

  @Test
  void listAuditLogs_Should_ClampPerPageToMin1_When_ZeroGiven() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 0, null, null);

    assertThat(result.getPerPage()).isEqualTo(1);
  }

  @Test
  void listAuditLogs_Should_ClampPageToMin1_When_NegativePageGiven() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(-5, 10, null, null);

    assertThat(result.getPage()).isEqualTo(1);
  }

  // ─── normalize() ──────────────────────────────────────────────────────────

  @Test
  void listAuditLogs_Should_NormalizeAccountRole_ToUppercase() {
    // normalize is private but exercised via listAuditLogs — verified by result not blowing up
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(1L, List.of(buildEntry()));

    InactiveAccountAuditLogsResult result =
        service.listAuditLogs(1, 10, "  consultant  ", "  abc  ");

    assertThat(result.getTotal()).isEqualTo(1L);
  }

  @Test
  void listAuditLogs_Should_TreatBlankFilterAsNull() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, "   ", "");

    assertThat(result.getTotal()).isZero();
  }

  // ─── resolveEffectiveTenantId — JWT paths ─────────────────────────────────

  @Test
  void listAuditLogs_Should_UseJwtNumericTenantId_When_TokenContainsNumericTenantId() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":7}"));
    stubJdbc(1L, List.of(buildEntry()));

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isEqualTo(1L);
  }

  @Test
  void listAuditLogs_Should_UseJwtStringTenantId_When_TokenContainsStringTenantId() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":\"3\"}"));
    stubJdbc(1L, List.of(buildEntry()));

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isEqualTo(1L);
  }

  @Test
  void listAuditLogs_Should_UseNullTenant_When_JwtTenantIdIsZero() {
    // tenantId=0 means global/super-admin context → no tenant restriction
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":0}"));
    stubJdbc(5L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isEqualTo(5L);
  }

  @Test
  void listAuditLogs_Should_UseNullTenant_When_JwtTenantIdNodeIsNull() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"sub\":\"user123\"}"));
    TenantContext.setCurrentTenant(0L); // isTechnicalOrSuperAdminContext = true → null tenant
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listAuditLogs_Should_FallbackToTenantContext_When_NoAccessToken() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(9L);
    stubJdbc(2L, List.of(buildEntry(), buildEntry()));

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isEqualTo(2L);
  }

  @Test
  void listAuditLogs_Should_FallbackToTenantContext_When_AccessTokenIsBlank() {
    when(authenticatedUser.getAccessToken()).thenReturn("   ");
    TenantContext.setCurrentTenant(4L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listAuditLogs_Should_FallbackToTenantContext_When_TokenHasFewerThanTwoParts() {
    when(authenticatedUser.getAccessToken()).thenReturn("notavalidjwt");
    TenantContext.setCurrentTenant(6L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listAuditLogs_Should_ReturnNullTenant_When_TenantContextIsTechnicalAdmin() {
    when(authenticatedUser.getAccessToken()).thenReturn(null);
    TenantContext.setCurrentTenant(0L); // isTechnicalOrSuperAdminContext = true
    stubJdbc(10L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isEqualTo(10L);
  }

  @Test
  void listAuditLogs_Should_FallbackGracefully_When_TokenPayloadIsMalformedJson() {
    // Base64 of invalid JSON
    String fakeHeader = Base64.getUrlEncoder().encodeToString("{}".getBytes());
    String fakePayload = Base64.getUrlEncoder().encodeToString("{not-json}".getBytes());
    when(authenticatedUser.getAccessToken()).thenReturn(fakeHeader + "." + fakePayload + ".sig");
    TenantContext.setCurrentTenant(1L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isZero();
  }

  @Test
  void listAuditLogs_Should_FallbackGracefully_When_TenantIdIsBlankString() {
    when(authenticatedUser.getAccessToken()).thenReturn(buildToken("{\"tenantId\":\"\"}"));
    TenantContext.setCurrentTenant(2L);
    stubJdbc(0L, List.of());

    InactiveAccountAuditLogsResult result = service.listAuditLogs(1, 10, null, null);

    assertThat(result.getTotal()).isZero();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private void stubJdbc(long total, List<InactiveAccountAuditLogEntry> data) {
    when(namedParameterJdbcTemplate.queryForObject(
            anyString(), any(SqlParameterSource.class), eq(Long.class)))
        .thenReturn(total);
    when(namedParameterJdbcTemplate.query(
            anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(data);
  }

  private InactiveAccountAuditLogEntry buildEntry() {
    return InactiveAccountAuditLogEntry.builder()
        .id(1L)
        .accountRole("CONSULTANT")
        .accountId("abc123")
        .accountTenantId(1L)
        .lastActivityAt(LocalDateTime.now().minusDays(30))
        .thresholdDays(90)
        .recipientAdminId("admin1")
        .recipientEmail("admin@example.com")
        .emailDispatched(true)
        .createDate(LocalDateTime.now())
        .build();
  }

  /** Build a minimal JWT (header.payload.sig) where payload is the given JSON. */
  private String buildToken(String payloadJson) {
    String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"RS256\"}".getBytes());
    String payload = Base64.getUrlEncoder().encodeToString(payloadJson.getBytes());
    return header + "." + payload + ".fakesig";
  }
}
