package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUsersRegisteredOnlyService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Proves that two replicas of the registered-only deletion scheduler run the workflow once.
 *
 * <p>Runs against real MariaDB because the exclusion depends on InnoDB gap locking: the {@code
 * PESSIMISTIC_WRITE} read in {@code ScheduledTaskClaimWriter} matches no row on the first claim,
 * and only a gap lock makes the loser wait until the winner has committed. H2 — including {@code
 * MODE=MariaDB} — emulates the syntax but not the locking, so both replicas would pass the empty
 * read and the loser's merge would silently turn into an UPDATE of the winner's fresh claim. The H2
 * variant of this test therefore proved nothing and failed by timing.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT {

  private static final String TASK_NAME = "registered-only-user-deletion";

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
    registry.add("user.registeredonly.deleteWorkflow.cron", () -> "0 0 0 1 1 ?");
  }

  @Autowired private ScheduledTaskClaimRepository claimRepository;
  @Autowired private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  @AfterEach
  void deleteReplicaProofClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @Test
  void twoSchedulerInstancesTriggerOneRegisteredOnlyDeletionBatch() throws Exception {
    var deletionService = mock(DeleteUsersRegisteredOnlyService.class);
    var tenantContextProvider = mock(TenantContextProvider.class);
    var first = newScheduler(deletionService, tenantContextProvider);
    var second = newScheduler(deletionService, tenantContextProvider);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult = executor.submit(() -> run(first, ready, start));
      var secondResult = executor.submit(() -> run(second, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult.get(15, TimeUnit.SECONDS);
      secondResult.get(15, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    verify(tenantContextProvider, times(1)).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(deletionService, times(1)).deleteUserAccountsTimeSensitive();
    verify(deletionService, times(1)).deleteUserAccountsTimeInsensitive();
    assertThat(claimRepository.findById(TASK_NAME)).isPresent();
  }

  private DeleteUsersRegisteredOnlyScheduler newScheduler(
      DeleteUsersRegisteredOnlyService deletionService,
      TenantContextProvider tenantContextProvider) {
    var scheduler =
        new DeleteUsersRegisteredOnlyScheduler(
            deletionService, tenantContextProvider, taskClaimService);
    ReflectionTestUtils.setField(scheduler, "userRegisteredOnlyDeleteWorkflowEnabled", true);
    ReflectionTestUtils.setField(
        scheduler, "userRegisteredOnlyDeleteWorkflowAfterSessionPurgeEnabled", true);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofHours(12));
    return scheduler;
  }

  private void run(
      DeleteUsersRegisteredOnlyScheduler scheduler, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    scheduler.performDeletionWorkflow();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent replica proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
