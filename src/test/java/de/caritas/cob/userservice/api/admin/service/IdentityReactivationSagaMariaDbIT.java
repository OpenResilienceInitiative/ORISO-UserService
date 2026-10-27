package de.caritas.cob.userservice.api.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimWriter;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves the reactivation generation claim with PreDev's real MariaDB transaction semantics. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({
  IdentityReactivationSagaStore.class,
  IdentityReactivationRepairWriter.class,
  UsernameTranscoder.class,
  DeletionLifecycleService.class,
  ScheduledTaskClaimService.class,
  ScheduledTaskClaimWriter.class,
  IdentityReactivationSagaMariaDbIT.ErasureClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdentityReactivationSagaMariaDbIT {

  private static final String USER_ID = "reactivation-saga-proof";
  private static final String USERNAME = "marge.saga@dreambau.de";

  @Autowired private UserRepository userRepository;
  @Autowired private IdentityReactivationSagaStore sagaStore;
  @Autowired private IdentityReactivationRepairWriter repairWriter;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private IdentityReactivator identityReactivator;
  @MockitoBean private IdentityDeactivator identityDeactivator;

  @Autowired private ScheduledTaskClaimService claims;
  @Autowired private ScheduledTaskClaimRepository claimRows;
  @Autowired private EntityManager entityManager;
  @Autowired private DataSource dataSource;
  @Autowired private ErasureClock erasureClock;

  private TransactionTemplate transactionTemplate;

  @TestConfiguration
  static class ErasureClockConfiguration {
    @Bean
    @Primary
    ErasureClock erasureClock() {
      return new ErasureClock();
    }
  }

  static final class ErasureClock extends Clock {
    Instant now = Instant.parse("2026-10-25T12:00:00.123456Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC required");
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @BeforeEach
  void setUp() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    deleteProofUser();
    transactionTemplate.executeWithoutResult(ignored -> userRepository.save(deletedUser()));
  }

  @AfterEach
  void tearDown() {
    deleteProofUser();
  }

  @Test
  void crashWindowLeavesCommittedClaimVisibleBeforeKeycloakStarts() {
    IdentityReactivationOperation operation = sagaStore.begin(USERNAME, USERNAME, 40L);

    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    assertThat(persisted.getReactivationOperationId()).isEqualTo(operation.operationId());
    assertThat(persisted.getReactivationOperationStartedAt()).isNotNull();
  }

  @Test
  void successfulFinalizeClearsOnlyItsGenerationInSameCommit() {
    IdentityReactivationOperation operation = sagaStore.begin(USERNAME, USERNAME, 40L);

    sagaStore.reactivateAndCommit(operation, "NewPassw0rd!");

    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getDeleteDate()).isNull();
    assertThat(persisted.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.ACTIVE);
    assertThat(persisted.getReactivationOperationId()).isNull();
  }

  @Test
  void unmutatedConflictCommitsClaimReleaseThroughSpringTransactionProxy() {
    IdentityReactivationOperation operation = sagaStore.begin(USERNAME, USERNAME, 40L);
    doThrow(new ConflictException("identity mismatch"))
        .when(identityReactivator)
        .reactivateUser(any(), any(), any(), any(), any());

    IdentityReactivationUnmutatedException failure =
        assertThrows(
            IdentityReactivationUnmutatedException.class,
            () -> sagaStore.reactivateAndCommit(operation, "NewPassw0rd!"));

    assertThat(failure.originalFailure()).isInstanceOf(ConflictException.class);
    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(persisted.getReactivationOperationId()).isNull();
    assertThat(persisted.getReactivationOperationStartedAt()).isNull();
  }

  @Test
  void lateCompensationCannotDisableASecondSuccessfulGeneration() {
    IdentityReactivationOperation first = sagaStore.begin(USERNAME, USERNAME, 40L);
    assertThat(repairWriter.retry(first.userId(), first.operationId())).isTrue();
    IdentityReactivationOperation second = sagaStore.begin(USERNAME, USERNAME, 40L);

    assertThat(repairWriter.compensate(first, new IllegalStateException("late callback")))
        .isFalse();

    verify(identityDeactivator, times(1)).deactivateUser(USER_ID);
    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getReactivationOperationId()).isEqualTo(second.operationId());
    assertThat(persisted.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
  }

  @Test
  void aColdJpaOwnerRetainsTheNullableErasureCursorAcrossNaturalLeaseExpiry() {
    String task = "reactivation-secret-erasure-v1";
    claimRows.deleteById(task);
    try {
      erasureClock.now = Instant.parse("2026-10-25T12:00:00.123456789Z");
      var first = claims.tryClaimLease(task, Duration.ofMinutes(5)).orElseThrow();
      assertThat(first.claimedUntil().getNano()).isEqualTo(123456000);
      assertThat(claimRows.findById(task).orElseThrow().getReactivationErasureAfterAccountId())
          .isNull();
      transactionTemplate.executeWithoutResult(
          ignored -> {
            var row = claimRows.findByTaskNameForUpdate(task).orElseThrow();
            row.setReactivationErasureAfterAccountId("10000000-0000-4000-8000-000000000099");
            claimRows.saveAndFlush(row);
            entityManager.clear();
          });
      assertThat(claims.tryClaimLease(task, Duration.ofMinutes(5))).isEmpty();
      erasureClock.now = first.claimedUntil().toInstant(ZoneOffset.UTC);
      var second = claims.tryClaimLease(task, Duration.ofMinutes(5)).orElseThrow();
      assertThat(second.claimedUntil()).isAfter(first.claimedUntil());
      transactionTemplate.executeWithoutResult(
          ignored -> {
            entityManager.clear();
            var loaded = claimRows.findById(task).orElseThrow();
            assertThat(loaded.getClaimedAt()).isEqualTo(first.claimedUntil());
            assertThat(loaded.getClaimedUntil()).isEqualTo(second.claimedUntil());
            assertThat(loaded.getReactivationErasureAfterAccountId())
                .isEqualTo("10000000-0000-4000-8000-000000000099");
          });
    } finally {
      claimRows.deleteById(task);
    }
  }

  @Test
  void aStaleFractionalLeaseCannotWriteThePersistedErasureCursorThroughTheActualProxy() {
    String task = "reactivation-secret-erasure-v1";
    claimRows.deleteById(task);
    var jdbc = new JdbcTemplate(dataSource);
    try {
      erasureClock.now = Instant.parse("2026-10-25T12:00:00.654321987Z");
      var first = claims.tryClaimLease(task, Duration.ofMinutes(5)).orElseThrow();
      assertThat(
              claims.runIfHeld(
                  first,
                  () ->
                      jdbc.update(
                          "update scheduled_task_claim set reactivation_erasure_after_account_id=? where task_name=?",
                          "10000000-0000-4000-8000-000000000099",
                          task)))
          .isTrue();
      var adjacent =
          new ScheduledTaskClaimService.ClaimLease(task, first.claimedUntil().minusNanos(1000));
      assertThat(
              claims.runIfHeld(
                  adjacent,
                  () -> {
                    throw new AssertionError("foreign lease reached local write");
                  }))
          .isFalse();
      erasureClock.now = first.claimedUntil().toInstant(ZoneOffset.UTC);
      assertThat(
              claims.runIfHeld(
                  first,
                  () -> {
                    throw new AssertionError("expired lease reached local write");
                  }))
          .isFalse();
      var second = claims.tryClaimLease(task, Duration.ofMinutes(5)).orElseThrow();
      assertThat(
              claims.runIfHeld(
                  first,
                  () -> {
                    throw new AssertionError("stale owner reached local write");
                  }))
          .isFalse();
      assertThat(
              claims.runIfHeld(
                  second,
                  () ->
                      jdbc.update(
                          "update scheduled_task_claim set reactivation_erasure_after_account_id=null where task_name=?",
                          task)))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from scheduled_task_claim where task_name=?",
                  Integer.class,
                  task))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select reactivation_erasure_after_account_id from scheduled_task_claim where task_name=?",
                  String.class,
                  task))
          .isNull();
    } finally {
      claimRows.deleteById(task);
    }
  }

  private User deletedUser() {
    var user = new User(USER_ID, null, USERNAME, USERNAME, true);
    var now = LocalDateTime.now(ZoneOffset.UTC);
    user.setCreateDate(now);
    user.setUpdateDate(now);
    user.setTenantId(40L);
    user.setDeleteDate(now.minusDays(3));
    user.setDeletionReadOnlyUntil(now.minusMinutes(1));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    return user;
  }

  private void deleteProofUser() {
    transactionTemplate.executeWithoutResult(
        ignored -> userRepository.findById(USER_ID).ifPresent(userRepository::delete));
  }
}
