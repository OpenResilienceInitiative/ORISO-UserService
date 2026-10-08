package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountInactivitySchedulerTest {
  @Test
  void schedulerIsAbsentUntilExplicitlyEnabled() {
    try (var context = context(false)) {
      assertThat(context.getBeansOfType(AccountInactivityScheduler.class)).isEmpty();
    }
  }

  @Test
  void explicitlyEnabledSchedulerDefaultsToDryRun() {
    try (var context = context(true)) {
      var lifecycle = context.getBean(AccountInactivityService.class);
      lifecycle.assignAtCreation("due", 1L, 1, 1, Instant.parse("2026-01-01T00:00:00Z"));
      assertThat(lifecycle.candidateReport("", 200))
          .singleElement()
          .satisfies(
              candidate -> {
                assertThat(candidate.snapshot().identityId()).isEqualTo("due");
                assertThat(candidate.plannedAction())
                    .isEqualTo(AccountInactivityService.PlannedAction.DELETE);
              });
      context.getBean(AccountInactivityScheduler.class).scan();
      assertThat(lifecycle.snapshot("due").orElseThrow().status())
          .isEqualTo(AccountInactivityService.Status.ACTIVE);
    }
  }

  private AnnotationConfigApplicationContext context(boolean enabled) {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:scheduler" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id"
            + " BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,last_activity"
            + " TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,status VARCHAR(20) NOT"
            + " NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    var context = new AnnotationConfigApplicationContext();
    if (enabled)
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("test", Map.of("account.inactivity.enabled", "true")));
    context.registerBean(
        AccountInactivityService.class,
        () ->
            new AccountInactivityService(
                jdbc,
                new DataSourceTransactionManager(ds),
                Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC),
                new AccountInactivityEffects() {
                  public Set<Role> currentRoles(String id) {
                    return Set.of(Role.ASKER);
                  }

                  public boolean delete(String id) {
                    throw new AssertionError("Dry-run must not delete");
                  }

                  public boolean suspend(String id) {
                    throw new AssertionError("Dry-run must not suspend");
                  }

                  public boolean reactivate(String id) {
                    throw new AssertionError("Dry-run must not reactivate");
                  }
                }));
    context.register(AccountInactivityScheduler.class);
    context.refresh();
    return context;
  }
}
