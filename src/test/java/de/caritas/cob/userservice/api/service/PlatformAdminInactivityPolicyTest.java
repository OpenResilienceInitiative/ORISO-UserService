package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class PlatformAdminInactivityPolicyTest {
  private static final String POLICY =
      """
      {"askerMonths":12,"consultantMonths":36,"otherMonths":48,"revision":7}
      """;

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-15T11:00:00.123456789Z"), ZoneOffset.UTC);
  private AuthenticatedUser caller;
  private MockRestServiceServer server;
  private AccountInactivityService lifecycle;
  private AccountInactivityEnrollmentService enrollment;

  @BeforeEach
  void setUp() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:platform-enrollment-" + UUID.randomUUID(), "sa", "");
    // Keep the in-memory database alive across the lifecycle's individual JDBC calls.
    ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
    var jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    lifecycle =
        new AccountInactivityService(
            jdbc,
            new DataSourceTransactionManager(ds),
            clock,
            new AccountInactivityEnrollmentTest.ExternalEffects());
    var rest = new RestTemplate();
    server = MockRestServiceServer.bindTo(rest).build();
    var factory = new TenantServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "restTemplate", rest);
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "https://tenant.test");
    caller = mock(AuthenticatedUser.class);
    var headers = new SecurityHeaderSupplier(caller);
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-Token");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    enrollment = new AccountInactivityEnrollmentService(factory, lifecycle, clock, headers);
  }

  @Test
  void platformAdminReceivesPlatformPolicyWithoutATenantZeroRecord() {
    when(caller.getAccessToken()).thenReturn("platform-caller-token");
    // Only the controls route exists: any attempt to fetch /tenant/public/id/0 fails this test.
    server
        .expect(requestTo("https://tenant.test/tenantadmin/controls"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer platform-caller-token"))
        .andRespond(
            withSuccess(
                "{\"accountInactivitySettings\":" + POLICY + "}", MediaType.APPLICATION_JSON));

    var captured =
        TechnicalAccessTokenContext.callWith(
            "ambient-service-token",
            () -> enrollment.capture(0L, AccountInactivityEnrollmentService.Group.OTHER));
    assertThat(captured.months()).isEqualTo(48);
    assertThat(captured.revision()).isEqualTo(7);
    assertThat(captured.capturedAt()).isEqualTo(Instant.parse("2026-10-15T11:00:00.123456Z"));

    enrollment.enroll("platform-admin", 0L, captured);
    // Re-enrollment must not reread defaults or replace the person's assigned policy.
    enrollment.enroll("platform-admin", 0L, AccountInactivityEnrollmentService.Group.OTHER);
    var assigned = lifecycle.snapshot("platform-admin").orElseThrow();
    assertThat(assigned.tenantId()).isZero();
    assertThat(assigned.assignedMonths()).isEqualTo(48);
    assertThat(assigned.revision()).isEqualTo(7);
    assertThat(assigned.dueAt()).isEqualTo(Instant.parse("2030-10-15T11:00:00.123456Z"));
    assertThat(assigned.status()).isEqualTo(AccountInactivityService.Status.ACTIVE);
    server.verify();
  }

  @Test
  void platformPolicyDoesNotFallBackToAnAmbientServiceTokenWithoutACaller() {
    assertThatThrownBy(
            () ->
                TechnicalAccessTokenContext.callWith(
                    "ambient-service-token",
                    () -> enrollment.capture(0L, AccountInactivityEnrollmentService.Group.OTHER)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 503})
  void unavailablePlatformPolicyFailsWithoutCreatingALifecycle(int status) {
    when(caller.getAccessToken()).thenReturn("platform-caller-token");
    server
        .expect(requestTo("https://tenant.test/tenantadmin/controls"))
        .andRespond(withStatus(HttpStatus.valueOf(status)));

    assertThatThrownBy(
            () ->
                enrollment.enroll(
                    "platform-admin", 0L, AccountInactivityEnrollmentService.Group.OTHER))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    assertThat(lifecycle.snapshot("platform-admin")).isEmpty();
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"accountInactivitySettings\":{\"otherMonths\":0,\"revision\":7}}",
        "{\"accountInactivitySettings\":{\"otherMonths\":48}}",
        "{\"accountInactivitySettings\":{\"otherMonths\":48,\"revision\":-1}}"
      })
  void invalidPlatformPolicyStillBlocksEnrollment(String body) {
    when(caller.getAccessToken()).thenReturn("platform-caller-token");
    server
        .expect(requestTo("https://tenant.test/tenantadmin/controls"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () ->
                enrollment.enroll(
                    "platform-admin", 0L, AccountInactivityEnrollmentService.Group.OTHER))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    assertThat(lifecycle.snapshot("platform-admin")).isEmpty();
    server.verify();
  }

  @Test
  void nullTenantKeepsUsingTheSingleTenantPolicy() {
    server
        .expect(requestTo("https://tenant.test/tenant/public/single"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(
            withSuccess(
                "{\"settings\":{\"tenantAdminControls\":{\"accountInactivitySettings\":"
                    + POLICY
                    + "}}}",
                MediaType.APPLICATION_JSON));

    var captured = enrollment.capture(null, AccountInactivityEnrollmentService.Group.CONSULTANT);
    assertThat(captured.months()).isEqualTo(36);
    assertThat(captured.revision()).isEqualTo(7);
    server.verify();
  }
}
