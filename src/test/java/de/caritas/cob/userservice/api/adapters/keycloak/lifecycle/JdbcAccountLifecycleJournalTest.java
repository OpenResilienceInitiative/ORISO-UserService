package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Local SQL transaction evidence only; native Keycloak/MariaDB acceptance is a separate gate. */
class JdbcAccountLifecycleJournalTest {
  private final JdbcDataSource source = new JdbcDataSource();
  private JdbcTemplate jdbc;
  private JdbcAccountLifecycleJournal journal;
  private AccountLifecycleCodec codec;
  private final UUID id = UUID.randomUUID();
  private CreationIdentity identity;

  @BeforeEach
  void database() throws Exception {
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    String migration =
        Files.readString(
            Path.of(
                "src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367/migrate.sql"));
    for (String sql : migration.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    jdbc.execute("CREATE TABLE local_saga_record(id VARCHAR(36) PRIMARY KEY)");
    codec = new AccountLifecycleCodec(new byte[32]);
    journal =
        new JdbcAccountLifecycleJournal(
            jdbc,
            new DataSourceTransactionManager(source),
            codec,
            Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC),
            300);
    identity =
        new CreationIdentity(
            new CreationScope(
                "7",
                "ASKER",
                "/ordinary/asker",
                new Origin(
                    "REGISTRATION",
                    "registration:one",
                    "agency:12",
                    null,
                    null,
                    null,
                    List.of("12"))),
            "a".repeat(64));
  }

  @Test
  void creationIntentAndFingerprintExistBeforeAnyRemoteResponse() {
    var execution = journal.begin(id, identity);
    assertEquals(0, execution.generation());
    assertEquals(
        codec.canonical(identity),
        jdbc.queryForObject(
            "SELECT identity_json FROM identity_lifecycle_attempt WHERE id=?",
            String.class,
            id.toString()));
    assertEquals("CREATION_REQUESTED", status());
    assertEquals(0, pending());
  }

  @Test
  void localRollbackRollsBackBothDomainSuccessAndCommitIntent() {
    var execution = opened();
    assertThrows(
        IllegalStateException.class,
        () ->
            journal.inLocalSaga(
                id,
                execution,
                row -> {
                  jdbc.update("INSERT INTO local_saga_record(id) VALUES (?)", id.toString());
                  journal.prepareCommitInSaga(id, execution);
                  throw new IllegalStateException("later local failure");
                }));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM local_saga_record", Integer.class));
    assertEquals(0, pending());
    assertEquals("OPEN", status());
    journal.requestRecovery(id, execution);
    assertEquals("RECOVERY_REQUESTED", status());
    assertEquals(1, pending());
  }

  @Test
  void committedOutboxSurvivesProcessLossWithoutAfterCommitCallback() {
    var execution = opened();
    journal.inLocalSaga(
        id,
        execution,
        row -> {
          jdbc.update("INSERT INTO local_saga_record(id) VALUES (?)", id.toString());
          journal.prepareCommitInSaga(id, execution);
          return null;
        }); // no ambient callback or coordinator object survives this simulated process boundary
    assertEquals("COMMIT_REQUESTED", status());
    assertEquals(1, pending());
    var restarted =
        new JdbcAccountLifecycleJournal(
            jdbc, new DataSourceTransactionManager(source), codec, Clock.systemUTC(), 300);
    var remote = mock(IdentityProvisioningCommands.class);
    when(remote.commit(eq(id), any()))
        .thenReturn(
            new AttemptResult(
                id, "account", "COMMITTED", execution, "proof", null, "terminal-receipt"));
    new AccountLifecycleDispatcher(remote, restarted, mock(AccountLifecycleEffects.class), codec)
        .dispatch(id);
    assertEquals("COMMITTED", status());
    assertEquals(0, pending());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM local_saga_record", Integer.class));
  }

  @Test
  void recoveryCannotStealLocallyCommittedSaga() {
    var execution = opened();
    journal.inLocalSaga(
        id,
        execution,
        row -> {
          journal.prepareCommitInSaga(id, execution);
          return null;
        });
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> journal.requestRecovery(id, execution));
    assertEquals("COMMIT_REQUESTED", status());
    assertEquals(1, pending());
  }

  @Test
  void uncertainCreateIsFencedByDurableRecoveryIntentInsteadOfSecondCreate() {
    var execution = journal.begin(id, identity);
    journal.createUncertain(id, execution);
    journal.claimExpired();
    assertEquals("RECOVERY_REQUESTED", status());
    assertEquals(1, pending());
    assertThrows(RuntimeException.class, () -> journal.begin(UUID.randomUUID(), identity));
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM identity_lifecycle_attempt", Integer.class));
  }

  @Test
  void staleEffectScopeCannotStartDownstreamWorkAfterRecoveryClaim() {
    var execution = opened();
    var effects =
        new JdbcOwnedLifecycleEffects(
            jdbc,
            new DataSourceTransactionManager(source),
            mock(JdbcOwnedLifecycleEffects.CleanupOperations.class),
            codec);
    var captured =
        new java.util.concurrent.atomic.AtomicReference<JdbcOwnedLifecycleEffects.Scope>();
    journal.inLocalSaga(
        id,
        execution,
        row -> {
          captured.set(effects.capture(row));
          return null;
        });
    journal.requestRecovery(id, execution);
    var local =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(source));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            local.execute(
                status -> {
                  captured.get().privateRoom().started("old-session-alias");
                  return null;
                }));
    assertEquals(
        0, jdbc.queryForObject("SELECT COUNT(*) FROM identity_lifecycle_effect", Integer.class));
  }

  @Test
  void startedRemoteEffectSurvivesOuterRollbackAndCannotBeSilentlyDeclaredCleaned() {
    var execution = opened();
    var effects =
        new JdbcOwnedLifecycleEffects(
            jdbc,
            new DataSourceTransactionManager(source),
            mock(JdbcOwnedLifecycleEffects.CleanupOperations.class),
            codec);
    assertThrows(
        IllegalStateException.class,
        () ->
            journal.inLocalSaga(
                id,
                execution,
                row -> {
                  effects.started(
                      row, JdbcOwnedLifecycleEffects.Kind.MATRIX_USER, "@synthetic:example.test");
                  throw new IllegalStateException(
                      "timeout after request may have reached remote service");
                }));
    assertEquals(
        "STARTED",
        jdbc.queryForObject("SELECT state FROM identity_lifecycle_effect", String.class));
    assertEquals("OPEN", status());
    assertEquals(0, pending());
  }

  @Test
  void outerRollbackCannotUndoAlreadyDispatchedImmutableCommitReceipt() {
    var execution = opened();
    journal.inLocalSaga(
        id,
        execution,
        row -> {
          journal.prepareCommitInSaga(id, execution);
          return null;
        });
    var remote = mock(IdentityProvisioningCommands.class);
    when(remote.commit(eq(id), any()))
        .thenReturn(
            new AttemptResult(id, "account", "COMMITTED", execution, "proof", null, "terminal"));
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(source));
    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.execute(
                status -> {
                  new AccountLifecycleDispatcher(
                          remote, journal, mock(AccountLifecycleEffects.class), codec)
                      .dispatch(id);
                  throw new IllegalStateException("unrelated outer rollback after remote commit");
                }));
    assertEquals("COMMITTED", status());
    assertEquals(0, pending());
    verify(remote).commit(eq(id), any());
  }

  @Test
  void workflowResumeFindsOnlyItsExactSavedAccountAndTenant() {
    var execution = opened();
    var found =
        journal.findWorkflow("REGISTRATION", "registration:one", 7L, "account").orElseThrow();
    assertEquals(id, found.id());
    assertEquals(execution, found.execution());
    assertTrue(journal.findWorkflow("REGISTRATION", "registration:one", 8L, "account").isEmpty());
    assertTrue(journal.findWorkflow("REGISTRATION", "registration:one", 7L, "foreign").isEmpty());
    assertTrue(journal.findWorkflow("INVITATION", "registration:one", 7L, "account").isEmpty());
  }

  @Test
  void failedInvitationCanStartAnotherAttemptOnlyAfterCompleteOwnedCompensation() {
    var execution = opened();
    journal.requestRecovery(id, execution);
    assertThrows(RuntimeException.class, () -> journal.begin(UUID.randomUUID(), identity));
    var remote = mock(IdentityProvisioningCommands.class);
    when(remote.recover(eq(id), any()))
        .thenAnswer(
            invocation -> {
              RecoverRequest request = invocation.getArgument(1);
              return new AttemptResult(
                  id,
                  "account",
                  "RECOVERY_CLAIMED",
                  new ExecutionRef(request.recoveryId(), 1),
                  "proof",
                  "recovery",
                  null);
            });
    when(remote.compensate(eq(id), any()))
        .thenAnswer(
            invocation -> {
              CompensateRequest request = invocation.getArgument(1);
              return new AttemptResult(
                  id, "account", "COMPENSATED", request.execution(), "proof", null, "terminal");
            });
    var dispatcher =
        new AccountLifecycleDispatcher(remote, journal, mock(AccountLifecycleEffects.class), codec);
    dispatcher.dispatch(id);
    dispatcher.dispatch(id);
    dispatcher.dispatch(id);
    assertEquals("COMPENSATED", status());
    UUID retry = UUID.randomUUID();
    var next = journal.begin(retry, identity);
    assertEquals(0, next.generation());
    assertNotEquals(execution.executionId(), next.executionId());
    assertEquals("COMPENSATED", status());
  }

  private ExecutionRef opened() {
    var execution = journal.begin(id, identity);
    journal.created(
        id,
        identity,
        execution,
        new AttemptResult(id, "account", "OPEN", execution, "proof", null, null));
    return execution;
  }

  private String status() {
    return jdbc.queryForObject(
        "SELECT status FROM identity_lifecycle_attempt WHERE id=?", String.class, id.toString());
  }

  private int pending() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM identity_lifecycle_outbox WHERE status='PENDING'", Integer.class);
  }
}
