package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.workflow.accountinactivity.*;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AccountInactivityEnrollmentTest {
  @Test
  void newPeopleReceiveCurrentPolicyAndExistingPeopleRetainTheirSnapshot() {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:enrollment;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(ds);
    jdbc.execute("DROP TABLE IF EXISTS account_inactivity");
    jdbc.execute(
        "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    var clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC);
    var lifecycle =
        new AccountInactivityService(
            jdbc, new DataSourceTransactionManager(ds), clock, new ExternalEffects());
    var rest = new RestTemplate();
    var server = MockRestServiceServer.bindTo(rest).build();
    var factory = new TenantServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "restTemplate", rest);
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "https://tenant.test");
    var enrollment = new AccountInactivityEnrollmentService(factory, lifecycle, clock);
    server
        .expect(requestTo("https://tenant.test/tenant/public/id/1"))
        .andRespond(
            withSuccess(
                "{\"id\":1,\"settings\":{\"tenantAdminControls\":{\"accountInactivitySettings\":{\"askerMonths\":12,\"consultantMonths\":36,\"otherMonths\":48,\"revision\":7}}}}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://tenant.test/tenant/public/id/1"))
        .andRespond(
            withSuccess(
                "{\"id\":1,\"settings\":{\"tenantAdminControls\":{\"accountInactivitySettings\":{\"askerMonths\":18,\"consultantMonths\":30,\"otherMonths\":42,\"revision\":8}}}}",
                MediaType.APPLICATION_JSON));
    var captured = enrollment.capture(1L, AccountInactivityEnrollmentService.Group.ASKER);
    enrollment.enroll("first", 1L, captured);
    enrollment.enroll("first", 1L, AccountInactivityEnrollmentService.Group.OTHER);
    assertThat(lifecycle.snapshot("first").orElseThrow().assignedMonths()).isEqualTo(12);
    assertThat(lifecycle.snapshot("first").orElseThrow().revision()).isEqualTo(7);
    assertThat(lifecycle.snapshot("first").orElseThrow().dueAt())
        .isEqualTo(Instant.parse("2027-09-16T10:00:00Z"));
    enrollment.enroll("second", 1L, AccountInactivityEnrollmentService.Group.ASKER);
    assertThat(lifecycle.snapshot("second").orElseThrow().assignedMonths()).isEqualTo(18);
    assertThat(lifecycle.snapshot("second").orElseThrow().revision()).isEqualTo(8);
    assertThat(lifecycle.snapshot("first").orElseThrow().assignedMonths()).isEqualTo(12);
    server.verify();
  }

  static class ExternalEffects implements AccountInactivityEffects {
    public Set<Role> currentRoles(String id) {
      return Set.of(Role.ASKER);
    }

    public boolean delete(String id) {
      return true;
    }

    public boolean suspend(String id) {
      return true;
    }

    public boolean reactivate(String id) {
      return true;
    }
  }
}
