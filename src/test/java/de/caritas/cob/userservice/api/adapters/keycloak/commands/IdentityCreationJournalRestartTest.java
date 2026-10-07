package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.IdentityCreationAttemptRepository;
import de.caritas.cob.userservice.api.service.accountinvite.*;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * File-backed database and real transactional repository are reopened across a process boundary.
 */
class IdentityCreationJournalRestartTest {
  @TempDir Path directory;

  @Test
  void guestSessionPhaseAndCaughtActivationFailureSurviveRestartUntilConfirmedCommit()
      throws Exception {
    var source = dataSource();
    var attempt = UUID.randomUUID();
    var origin =
        IdentityCreationOrigin.checkedAnonymous(
            de.caritas.cob.userservice.api.adapters.web.dto.UserDTO.builder()
                .username("Anonymous-journal")
                .password("only-test-password")
                .termsAccepted("true")
                .postcode("00000")
                .consultingType("1")
                .build(),
            42L);
    KeycloakTaskCommands.CreationResult receipt;
    try (var context = open(source, true)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin, "Anonymous-journal");
      receipt =
          new KeycloakTaskCommands.CreationResult(
              attempt, "owned-guest", "owned-proof", "OPEN", execution.claim());
      journal.created(receipt, origin, execution);
      var tx =
          new org.springframework.transaction.support.TransactionTemplate(
              context.getBean(PlatformTransactionManager.class));
      var owned = receipt;
      tx.executeWithoutResult(
          status -> {
            journal.acquireLocalSaga(owned);
            journal.captureAnonymousBootstrap(attempt, 17L);
            journal.prepareCommitInSaga(owned, origin);
          });
      tx.executeWithoutResult(status -> journal.recordAnonymousBootstrapFailure(attempt, 17L));
    }
    try (var context = open(source, false)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var persisted = journal.attempt(attempt);
      assertThat(persisted.getStatus()).isEqualTo("COMMIT_REQUESTED");
      assertThat(persisted.getBootstrapSessionId()).isEqualTo(17L);
      assertThat(persisted.getBootstrapExpiresAt()).isNotNull();
      assertThat(persisted.getBootstrapFailedAt()).isNotNull();
      assertThat(journal.pendingAnonymousBootstraps()).isEmpty();
      journal.finish(receipt, "COMMITTED");
      assertThat(journal.pendingAnonymousBootstraps())
          .singleElement()
          .satisfies(row -> assertThat(row.getBootstrapSessionId()).isEqualTo(17L));
    }
  }

  @Test
  void reopenedOpenSagaCannotBeReplayedOrCompensatedByADuplicateRegistration() throws Exception {
    var source = dataSource();
    UUID attempt = UUID.randomUUID();
    try (var context = open(source, true)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin(), "new-user");
      journal.created(
          new KeycloakTaskCommands.CreationResult(attempt, "new-account", "owned-proof", "OPEN"),
          origin(),
          execution);
    }
    try (var context = open(source, false)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      assertThat(journal.ownedAttempt("new-account").getStatus()).isEqualTo("OPEN");
      assertThat(journal.pending()).isEmpty();
      assertThatThrownBy(() -> journal.begin(UUID.randomUUID(), origin(), "new-user"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    }
  }

  @Test
  void concurrentRequestedCallIsDeniedUntilAnUncertainResultIsRecorded() throws Exception {
    var source = dataSource();
    UUID attempt = UUID.randomUUID();
    try (var context = open(source, true)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      journal.begin(attempt, origin(), "new-user");
      assertThatThrownBy(() -> journal.begin(UUID.randomUUID(), origin(), "new-user"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    }
  }

  @Test
  void recordedUnknownCreateResultReopensTheSameAttemptAfterRestart() throws Exception {
    var source = dataSource();
    UUID firstAttempt = UUID.randomUUID();
    var command =
        new KeycloakTaskCommands.AccountCreation(
            "new-user",
            "new@example.org",
            "New",
            "User",
            null,
            42L,
            "test-only-password",
            true,
            List.of("consultant"),
            "CONSULTANT");
    var failed = org.mockito.Mockito.mock(IdentityProvisioningCommands.class);
    org.mockito.Mockito.when(
            failed.create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("unknown transport result"));
    try (var context = open(source, true)) {
      var service =
          new IdentityAccountProvisioning(
              failed, context.getBean(IdentityCreationJournalWriter.class));
      assertThatThrownBy(() -> service.create(firstAttempt, command, origin()))
          .isInstanceOf(IllegalStateException.class);
    }
    var retried = org.mockito.Mockito.mock(IdentityProvisioningCommands.class);
    org.mockito.Mockito.when(
            retried.create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call ->
                new KeycloakTaskCommands.CreationResult(
                    call.getArgument(0), "new-account", "owned-proof", "OPEN"));
    try (var context = open(source, false)) {
      var service =
          new IdentityAccountProvisioning(
              retried, context.getBean(IdentityCreationJournalWriter.class));
      var receipt = service.create(UUID.randomUUID(), command, origin());
      assertThat(receipt.attemptId()).isEqualTo(firstAttempt);
      assertThat(
              context
                  .getBean(IdentityCreationJournalWriter.class)
                  .ownedAttempt("new-account")
                  .getStatus())
          .isEqualTo("OPEN");
    }
  }

  @Test
  void reopenedCompensationIntentRetainsOnlyItsOwnedReceiptAndCanFinish() throws Exception {
    var source = dataSource();
    UUID attempt = UUID.randomUUID();
    var receipt =
        new KeycloakTaskCommands.CreationResult(attempt, "new-account", "owned-proof", "OPEN");
    try (var context = open(source, true)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin(), "new-user");
      journal.created(receipt, origin(), execution);
      journal.request(receipt, origin(), "COMPENSATION_REQUESTED");
    }
    try (var context = open(source, false)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      assertThat(journal.pending())
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.getAccountId()).isEqualTo("new-account");
                assertThat(row.getCreationProof()).isEqualTo("owned-proof");
                assertThat(row.getAuthorizedAgencyIds()).isEqualTo("8");
              });
      assertThatThrownBy(
              () ->
                  journal.request(
                      new KeycloakTaskCommands.CreationResult(
                          attempt, "foreign", "owned-proof", "OPEN"),
                      origin(),
                      "COMPENSATION_REQUESTED"))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      journal.finish(receipt, "COMPENSATED");
      assertThat(journal.pending()).isEmpty();
      assertThat(journal.ownedAttempt("new-account").getStatus()).isEqualTo("COMPENSATED");
    }
  }

  @Test
  void expiredRequestedLeaseReopensSameAttemptButStaleExecutionCannotCaptureReceipt()
      throws Exception {
    var source = dataSource();
    var start = java.time.Instant.parse("2026-10-07T10:00:00Z");
    var initial = java.time.Clock.fixed(start, java.time.ZoneOffset.UTC);
    var expired = java.time.Clock.fixed(start.plusSeconds(61), java.time.ZoneOffset.UTC);
    IdentityCreationJournalWriter.CreationExecution oldExecution;
    try (var context = open(source, true, initial)) {
      oldExecution =
          context
              .getBean(IdentityCreationJournalWriter.class)
              .begin(UUID.randomUUID(), origin(), "new-user");
    }
    try (var context = open(source, false, expired)) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var newExecution = journal.begin(UUID.randomUUID(), origin(), "new-user");
      assertThat(newExecution.attemptId()).isEqualTo(oldExecution.attemptId());
      assertThat(newExecution.claim()).isNotEqualTo(oldExecution.claim());
      var receipt =
          new KeycloakTaskCommands.CreationResult(
              newExecution.attemptId(), "new-account", "owned-proof", "OPEN");
      assertThatThrownBy(() -> journal.created(receipt, origin(), oldExecution))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThatThrownBy(() -> journal.createResultUncertain(oldExecution, origin()))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      journal.created(receipt, origin(), newExecution);
      assertThat(journal.ownedAttempt("new-account").getStatus()).isEqualTo("OPEN");
    }
  }

  @Test
  void expiredOpenAutomaticallyRecordsRecoveryAndFencesStaleCommitAfterRestart() throws Exception {
    var source = dataSource();
    var start = java.time.Instant.parse("2026-10-07T10:00:00Z");
    UUID attempt = UUID.randomUUID();
    var receipt =
        new KeycloakTaskCommands.CreationResult(attempt, "new-account", "owned-proof", "OPEN");
    try (var context = open(source, true, java.time.Clock.fixed(start, java.time.ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin(), "new-user");
      journal.created(receipt, origin(), execution);
    }
    try (var context =
        open(
            source,
            false,
            java.time.Clock.fixed(start.plusSeconds(61), java.time.ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      assertThat(journal.reconciliationRequired())
          .singleElement()
          .satisfies(row -> assertThat(row.getStatus()).isEqualTo("RECOVERY_REQUESTED"));
      assertThatThrownBy(() -> journal.request(receipt, origin(), "COMMIT_REQUESTED"))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
  }

  @Test
  void nativeRecoveryClaimFencesTheOldExecutionFromAnyLocalWrite() throws Exception {
    var source = dataSource();
    var start = java.time.Instant.parse("2026-10-07T10:00:00Z");
    var attempt = UUID.randomUUID();
    KeycloakTaskCommands.CreationResult owned;
    try (var context = open(source, true, java.time.Clock.fixed(start, java.time.ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin(), "new-user");
      owned =
          new KeycloakTaskCommands.CreationResult(
              attempt, "new-account", "owned-proof", "OPEN", execution.claim());
      journal.created(owned, origin(), execution);
    }
    try (var context =
        open(
            source,
            false,
            java.time.Clock.fixed(start.plusSeconds(61), java.time.ZoneOffset.UTC))) {
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var candidate = journal.reconciliationRequired().get(0);
      journal.recovered(
          candidate,
          new KeycloakTaskCommands.RecoveryResult(
              attempt, "new-account", "owned-proof", "RECOVERY_CLAIMED"));
      var transaction =
          new org.springframework.transaction.support.TransactionTemplate(
              context.getBean(PlatformTransactionManager.class));
      var receipt = owned;
      assertThatThrownBy(
              () -> transaction.executeWithoutResult(status -> journal.acquireLocalSaga(receipt)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThatThrownBy(() -> journal.request(receipt, origin(), "COMMIT_REQUESTED"))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(journal.attempt(attempt).getStatus()).isEqualTo("LOCAL_CLEANUP_REQUESTED");
    }
  }

  @Test
  void recoveryCannotRaceLocalWritesAndTheirAtomicCommitIntent() throws Exception {
    var source = dataSource();
    var start = java.time.Instant.parse("2026-10-07T10:00:00Z");
    try (var live = open(source, true, java.time.Clock.fixed(start, java.time.ZoneOffset.UTC));
        var sweeper =
            open(
                source,
                false,
                java.time.Clock.fixed(start.plusSeconds(61), java.time.ZoneOffset.UTC));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var journal = live.getBean(IdentityCreationJournalWriter.class);
      var attempt = UUID.randomUUID();
      var execution = journal.begin(attempt, origin(), "new-user");
      var receipt =
          new KeycloakTaskCommands.CreationResult(
              attempt, "new-account", "owned-proof", "OPEN", execution.claim());
      journal.created(receipt, origin(), execution);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
      jdbc.execute("CREATE TABLE local_completion(attempt_id VARCHAR(36) PRIMARY KEY)");
      var active =
          executor.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(
                          live.getBean(PlatformTransactionManager.class))
                      .executeWithoutResult(
                          status -> {
                            journal.acquireLocalSaga(receipt);
                            jdbc.update(
                                "INSERT INTO local_completion(attempt_id) VALUES (?)",
                                attempt.toString());
                            entered.countDown();
                            try {
                              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException(
                                    "test did not release live transaction");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                            journal.prepareCommitInSaga(receipt, origin());
                          }));
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var recovery =
          executor.submit(
              () -> sweeper.getBean(IdentityCreationJournalWriter.class).reconciliationRequired());
      try {
        Thread.sleep(150);
        assertThat(recovery.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      active.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(recovery.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
      assertThat(journal.attempt(attempt).getStatus()).isEqualTo("COMMIT_REQUESTED");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_completion", Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void overlappingWorkflowCannotObtainOrCompensateTheActiveWorkflowsReceipt() throws Exception {
    var source = dataSource();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var finish = new java.util.concurrent.CountDownLatch(1);
    var commands = org.mockito.Mockito.mock(IdentityProvisioningCommands.class);
    var command =
        new KeycloakTaskCommands.AccountCreation(
            "new-user",
            "new@example.org",
            "New",
            "User",
            null,
            42L,
            "test-only-password",
            true,
            List.of("consultant"),
            "CONSULTANT");
    org.mockito.Mockito.when(
            commands.create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              if (!finish.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("test workflow did not release");
              return new KeycloakTaskCommands.CreationResult(
                  call.getArgument(0), "new-account", "owned-proof", "OPEN");
            });
    try (var context = open(source, true);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var service =
          new IdentityAccountProvisioning(
              commands, context.getBean(IdentityCreationJournalWriter.class));
      var active = executor.submit(() -> service.create(UUID.randomUUID(), command, origin()));
      try {
        assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command, origin()))
            .isInstanceOf(
                de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
      } finally {
        finish.countDown();
      }
      assertThat(active.get(10, java.util.concurrent.TimeUnit.SECONDS).accountId())
          .isEqualTo("new-account");
      org.mockito.Mockito.verify(commands, org.mockito.Mockito.times(1))
          .create(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      org.mockito.Mockito.verify(commands, org.mockito.Mockito.never())
          .compensate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
  }

  @Test
  void lastLocalDatabaseWriteAndCommitIntentRollBackTogetherAndSurviveCommittedRestart()
      throws Exception {
    var source = dataSource();
    UUID attempt = UUID.randomUUID();
    var receipt =
        new KeycloakTaskCommands.CreationResult(attempt, "new-account", "owned-proof", "OPEN");
    try (var context = open(source, true)) {
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
      jdbc.execute("CREATE TABLE local_completion (attempt_id VARCHAR(36) PRIMARY KEY)");
      var journal = context.getBean(IdentityCreationJournalWriter.class);
      var execution = journal.begin(attempt, origin(), "new-user");
      journal.created(receipt, origin(), execution);
      var transaction =
          new org.springframework.transaction.support.TransactionTemplate(
              context.getBean(PlatformTransactionManager.class));
      assertThatThrownBy(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        jdbc.update(
                            "INSERT INTO local_completion(attempt_id) VALUES (?)",
                            attempt.toString());
                        journal.prepareCommitInSaga(receipt, origin());
                        throw new IllegalStateException("last local step failed");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_completion", Integer.class))
          .isZero();
      assertThat(journal.ownedAttempt("new-account").getStatus()).isEqualTo("OPEN");
      assertThat(journal.pending()).isEmpty();
      transaction.executeWithoutResult(
          status -> {
            jdbc.update("INSERT INTO local_completion(attempt_id) VALUES (?)", attempt.toString());
            journal.prepareCommitInSaga(receipt, origin());
          });
    }
    try (var context = open(source, false)) {
      var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_completion", Integer.class))
          .isEqualTo(1);
      assertThat(context.getBean(IdentityCreationJournalWriter.class).pending())
          .singleElement()
          .satisfies(row -> assertThat(row.getStatus()).isEqualTo("COMMIT_REQUESTED"));
    }
  }

  private DataSource dataSource() {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:file:" + directory.resolve("journal") + ";MODE=MariaDB;DB_CLOSE_ON_EXIT=FALSE");
    source.setUser("sa");
    return source;
  }

  static AnnotationConfigApplicationContext open(DataSource source, boolean migrate)
      throws Exception {
    return open(source, migrate, java.time.Clock.systemUTC());
  }

  static AnnotationConfigApplicationContext open(
      DataSource source, boolean migrate, java.time.Clock clock) throws Exception {
    if (migrate)
      try (var connection = source.getConnection()) {
        new Liquibase(
                "db/changelog/changeset/20261007_task_identity_creation/changeSet.xml",
                new ClassLoaderResourceAccessor(),
                new JdbcConnection(connection))
            .update(new Contexts());
      }
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("isolated-creation-journal");
    context.registerBean(DataSource.class, () -> source);
    context.registerBean(java.time.Clock.class, () -> clock);
    context.register(JournalConfiguration.class);
    context.refresh();
    return context;
  }

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.context.annotation.Profile("isolated-creation-journal")
  @EnableTransactionManagement
  static class JournalConfiguration {
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan("de.caritas.cob.userservice.api.model");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(
          Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.show_sql", "false"));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(
        jakarta.persistence.EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    IdentityCreationAttemptRepository attempts(jakarta.persistence.EntityManagerFactory factory) {
      return new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
              SharedEntityManagerCreator.createSharedEntityManager(factory))
          .getRepository(IdentityCreationAttemptRepository.class);
    }

    @Bean
    IdentityCreationJournalWriter journal(
        IdentityCreationAttemptRepository attempts, java.time.Clock clock) {
      return new IdentityCreationJournalWriter(attempts, clock, 60);
    }
  }

  static IdentityCreationOrigin origin() {
    var invite =
        AccountInvite.builder()
            .id(7L)
            .tenantId(42L)
            .agencyId(8L)
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .purpose(AccountInvitePurpose.INVITE)
            .status(AccountInviteStatus.EMAIL_SENT)
            .build();
    return IdentityCreationOrigin.heldInvitation(
        invite, IdentityCreationOrigin.Kind.CONSULTANT, List.of("consultant"));
  }
}
