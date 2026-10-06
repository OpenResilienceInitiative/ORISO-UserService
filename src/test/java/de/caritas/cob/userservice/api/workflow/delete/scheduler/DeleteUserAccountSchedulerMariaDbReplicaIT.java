package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.model.ScheduledTaskClaim;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Proves that two replicas of the account-deletion scheduler run the workflow once.
 *
 * <p>Runs against real MariaDB because two missing-row {@code PESSIMISTIC_WRITE} reads can hold
 * compatible InnoDB gap locks and then deadlock when both replicas insert the claim. H2 — including
 * {@code MODE=MariaDB} — does not reproduce that locking behavior. The claim service must retry
 * MariaDB deadlock 1213 in a fresh transaction so one replica wins and the other returns cleanly.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DeleteUserAccountSchedulerMariaDbReplicaIT {

  private static final String TASK_NAME = "account-deletion";
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    if (System.getenv("LIQUIBASE_IT_DB_URL") == null) {
      DATABASE.start();
    }
  }

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> databaseSetting("LIQUIBASE_IT_DB_URL", DATABASE::getJdbcUrl, null));
    registry.add(
        "spring.datasource.username",
        () -> databaseSetting("LIQUIBASE_IT_DB_USERNAME", DATABASE::getUsername, "root"));
    registry.add(
        "spring.datasource.password",
        () -> databaseSetting("LIQUIBASE_IT_DB_PASSWORD", DATABASE::getPassword, "root"));
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
    registry.add("user.account.deleteworkflow.cron", () -> "0 0 0 1 1 ?");
  }

  private static String databaseSetting(
      String name, java.util.function.Supplier<String> containerValue, String externalDefault) {
    String configured = System.getenv(name);
    if (configured != null) {
      return configured;
    }
    return System.getenv("LIQUIBASE_IT_DB_URL") != null ? externalDefault : containerValue.get();
  }

  @Autowired private ScheduledTaskClaimRepository claimRepository;
  @Autowired private ScheduledTaskClaimService taskClaimService;
  @Autowired private ScheduledTaskClaimWriter claimWriter;

  @BeforeEach
  @AfterEach
  void deleteReplicaProofClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @RepeatedTest(30)
  void twoSchedulerInstancesTriggerOneAccountDeletionWorkflow() throws Exception {
    var deletionService = mock(DeleteUserAccountService.class);
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
    verify(deletionService, times(1)).deleteUserAccounts();
    assertThat(claimRepository.findById(TASK_NAME)).isPresent();
  }

  @Test
  void deadlockedReplicaWaitsForWinningFirstClaimToCommit() throws Exception {
    var bothReadAbsent = new CountDownLatch(2);
    var releaseWinner = new CountDownLatch(1);
    var deadlockSeen = new CountDownLatch(1);
    var databaseFailure = new AtomicReference<Throwable>();
    var pausedRepository = mock(ScheduledTaskClaimRepository.class, delegatesTo(claimRepository));
    doAnswer(
            invocation -> {
              var claim = claimRepository.findByTaskNameForUpdate(TASK_NAME);
              if (claim instanceof java.util.Optional<?> found && found.isEmpty()) {
                bothReadAbsent.countDown();
                await(bothReadAbsent);
              }
              return claim;
            })
        .when(pausedRepository)
        .findByTaskNameForUpdate(TASK_NAME);
    doAnswer(
            invocation -> {
              try {
                Object inserted = claimRepository.saveAndFlush(invocation.getArgument(0));
                await(releaseWinner);
                return inserted;
              } catch (RuntimeException failure) {
                databaseFailure.set(failure);
                deadlockSeen.countDown();
                throw failure;
              }
            })
        .when(pausedRepository)
        .saveAndFlush(any(ScheduledTaskClaim.class));

    var writerTarget = AopTestUtils.getUltimateTargetObject(claimWriter);
    ReflectionTestUtils.setField(writerTarget, "claimRepository", pausedRepository);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> taskClaimService.tryClaim(TASK_NAME, Duration.ofHours(12)));
      var second =
          executor.submit(() -> taskClaimService.tryClaim(TASK_NAME, Duration.ofHours(12)));
      if (!bothReadAbsent.await(5, TimeUnit.SECONDS)) {
        releaseWinner.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        throw new AssertionError("Both replicas did not reach the empty claim read");
      }
      assertThat(deadlockSeen.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(databaseFailure.get()).hasMessageContaining("Deadlock");
      releaseWinner.countDown();
      assertThat(
              java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
      ReflectionTestUtils.setField(writerTarget, "claimRepository", claimRepository);
    }
  }

  private DeleteUserAccountScheduler newScheduler(
      DeleteUserAccountService deletionService, TenantContextProvider tenantContextProvider) {
    var scheduler =
        new DeleteUserAccountScheduler(deletionService, tenantContextProvider, taskClaimService);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofHours(12));
    return scheduler;
  }

  private void run(
      DeleteUserAccountScheduler scheduler, CountDownLatch ready, CountDownLatch start) {
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
