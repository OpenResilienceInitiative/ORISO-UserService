package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** H2 SQL ownership/queue evidence; MariaDB syntax and locking require the separate native gate. */
class AccountLifecycleRetryFairnessSqlTest {
  private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
  private final JdbcDataSource source = new JdbcDataSource();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(new byte[32]);
  private final IdentityProvisioningCommands remote = mock(IdentityProvisioningCommands.class);
  private final JdbcOwnedLifecycleEffects.CleanupOperations downstream =
      mock(JdbcOwnedLifecycleEffects.CleanupOperations.class);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;

  @BeforeEach
  void database() throws Exception {
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    for (String sql :
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367/migrate.sql"))
            .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    for (String retrySql :
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367/retry-fairness.sql"))
            .split(";")) if (!retrySql.isBlank()) jdbc.execute(retrySql);
    when(remote.recover(any(), any()))
        .thenAnswer(
            call -> {
              UUID id = call.getArgument(0);
              RecoverRequest request = call.getArgument(1);
              return new AttemptResult(
                  id,
                  id.toString(),
                  "RECOVERY_CLAIMED",
                  new ExecutionRef(request.recoveryId(), request.expectedGeneration() + 1),
                  "proof:" + id,
                  "recovery:" + id,
                  null);
            });
    when(remote.commit(any(), any()))
        .thenAnswer(
            call -> {
              UUID id = call.getArgument(0);
              CommitRequest request = call.getArgument(1);
              return new AttemptResult(
                  id,
                  id.toString(),
                  "COMMITTED",
                  request.execution(),
                  "proof:" + id,
                  null,
                  "terminal:" + id);
            });
  }

  private JdbcAccountLifecycleJournal journal(Instant now) {
    return new JdbcAccountLifecycleJournal(
        jdbc,
        new DataSourceTransactionManager(source),
        codec,
        Clock.fixed(now, ZoneOffset.UTC),
        300);
  }

  private JdbcOwnedLifecycleEffects effects() {
    return new JdbcOwnedLifecycleEffects(jdbc, manager, downstream, codec);
  }

  private AccountLifecycleDispatcher dispatcher(JdbcAccountLifecycleJournal journal) {
    return new AccountLifecycleDispatcher(remote, journal, effects(), codec);
  }

  private ExecutionRef opened(JdbcAccountLifecycleJournal journal, UUID id) {
    var identity =
        codec.identity(
            new CreationScope(
                "7",
                "ASKER",
                "/human-roles/user",
                new Origin(
                    "REGISTRATION",
                    "registration:" + id,
                    "agency:12",
                    null,
                    null,
                    null,
                    List.of("12"))),
            new AccountCreation(
                "synthetic-" + id, null, null, null, "de", "synthetic-password", false));
    var execution = journal.begin(id, identity);
    journal.created(
        id,
        identity,
        execution,
        new AttemptResult(id, id.toString(), "OPEN", execution, "proof:" + id, null, null));
    return execution;
  }

  private UUID commitReady(JdbcAccountLifecycleJournal journal) {
    UUID id = UUID.randomUUID();
    var execution = opened(journal, id);
    journal.inLocalSaga(
        id,
        execution,
        row -> {
          journal.prepareCommitInSaga(id, execution);
          return null;
        });
    return id;
  }

  @Test
  void oneHundredUncertainOwnedCleanupsCannotStarveTheNextCommittedAccountAfterRestart() {
    var first = journal(NOW);
    var dispatcher = dispatcher(first);
    for (int n = 0; n < 100; n++) {
      UUID id = UUID.randomUUID();
      var execution = opened(first, id);
      first.inLocalSaga(
          id,
          execution,
          row -> {
            effects()
                .started(
                    row,
                    JdbcOwnedLifecycleEffects.Kind.MATRIX_USER,
                    "@blocked-" + id + ":matrix.example");
            return null;
          });
      first.requestRecovery(id, execution);
      dispatcher.dispatch(id);
      jdbc.update(
          "UPDATE identity_lifecycle_outbox SET created_at=? WHERE attempt_id=? AND status='PENDING'",
          Timestamp.from(NOW.minusSeconds(1000 - n)),
          id.toString());
    }
    UUID ready = commitReady(first); // process loss before immediate afterCommit dispatch
    var originalBodies =
        jdbc.queryForList(
            "SELECT id,operation,body_json FROM identity_lifecycle_outbox WHERE status='PENDING' ORDER BY id");

    dispatcher.retryPending();
    assertEquals("COMMIT_REQUESTED", status(ready));
    dispatcher(journal(NOW)).retryPending(); // new journal/dispatcher, same actual durable database

    assertEquals("COMMITTED", status(ready));
    verify(remote).commit(eq(ready), any());
    verify(remote, never()).compensate(any(), any());
    assertEquals(
        100,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity_lifecycle_effect WHERE state='STARTED'", Integer.class));
    assertEquals(
        100,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity_lifecycle_outbox WHERE status='PENDING'",
            Integer.class));
    assertEquals(
        100,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity_lifecycle_attempt WHERE status='LOCAL_CLEANUP_REQUESTED'",
            Integer.class));
    for (var body : originalBodies)
      assertEquals(
          body.get("body_json"),
          jdbc.queryForObject(
              "SELECT body_json FROM identity_lifecycle_outbox WHERE id=?",
              String.class,
              body.get("id")));
    dispatcher(journal(NOW.plusSeconds(31)))
        .retryPending(); // bounded reservations expire; unknown effects remain pending
    assertEquals(
        100,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity_lifecycle_outbox WHERE status='PENDING'",
            Integer.class));
    verifyNoInteractions(downstream);
  }

  @Test
  void twoReplicaReservationsAreDisjointAndCrashReservationsExpireWithoutDroppingIntents()
      throws Exception {
    var creator = journal(NOW);
    var expected = new HashSet<UUID>();
    for (int n = 0; n < 101; n++) expected.add(commitReady(creator));
    var start = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<List<UUID>> claim =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            return journal(NOW).pending(60);
          };
      var a = pool.submit(claim);
      var b = pool.submit(claim);
      var pageA = a.get(10, TimeUnit.SECONDS);
      var pageB = b.get(10, TimeUnit.SECONDS);
      assertTrue(Collections.disjoint(pageA, pageB));
      assertTrue(pageA.size() <= 60 && pageB.size() <= 60);
      var all = new HashSet<>(pageA);
      all.addAll(pageB);
      assertEquals(expected, all);
      assertEquals(List.of(), journal(NOW).pending(100));
      var restarted = journal(NOW.plusSeconds(31));
      var expired = new HashSet<>(restarted.pending(100));
      expired.addAll(restarted.pending(100));
      assertEquals(expected, expired);
      assertEquals(
          101,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM identity_lifecycle_outbox WHERE status='PENDING'",
              Integer.class));
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void reservationCommitsPhysicallyBeforeDispatchAndSurvivesAnUnrelatedOuterRollback() {
    var first = journal(NOW);
    UUID a = commitReady(first), b = commitReady(first);
    var outer = new TransactionTemplate(manager);
    var held =
        outer.execute(
            status -> {
              var ids = first.pending(1);
              status.setRollbackOnly();
              return ids;
            });
    assertEquals(1, held.size());
    var next = journal(NOW).pending(1);
    assertEquals(1, next.size());
    assertTrue(Collections.disjoint(held, next));
    assertEquals(Set.of(a, b), new HashSet<>(List.of(held.getFirst(), next.getFirst())));
  }

  @Test
  void migrationBackfillsAnExistingPendingIntentWithoutChangingItsImmutableBody() throws Exception {
    var upgraded = new JdbcDataSource();
    upgraded.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    var legacy = new JdbcTemplate(upgraded);
    var directory =
        Path.of("src/main/resources/db/changelog/changeset/20261021_identity_lifecycle_367");
    for (String sql : Files.readString(directory.resolve("migrate.sql")).split(";"))
      if (!sql.isBlank()) legacy.execute(sql);
    var current =
        new JdbcAccountLifecycleJournal(
            legacy,
            new DataSourceTransactionManager(upgraded),
            codec,
            Clock.fixed(NOW, ZoneOffset.UTC),
            300);
    UUID id = UUID.randomUUID();
    var execution = opened(current, id);
    var row =
        legacy.queryForObject(
            "SELECT identity_json FROM identity_lifecycle_attempt WHERE id=?",
            String.class,
            id.toString());
    var identity = codec.read(row, CreationIdentity.class);
    UUID outbox = UUID.randomUUID();
    String body =
        codec.canonical(
            new CommitRequest(
                identity,
                execution,
                id.toString(),
                "proof:" + id,
                new LocalOutcome(3, outbox, "a".repeat(64))));
    legacy.update(
        "UPDATE identity_lifecycle_attempt SET status='COMMIT_REQUESTED' WHERE id=?",
        id.toString());
    legacy.update(
        "INSERT INTO identity_lifecycle_outbox(id,attempt_id,operation,body_json,status,created_at) VALUES (?,?,?,?,'PENDING',?)",
        outbox.toString(),
        id.toString(),
        "COMMIT",
        body,
        Timestamp.from(NOW));
    for (String sql : Files.readString(directory.resolve("retry-fairness.sql")).split(";"))
      if (!sql.isBlank()) legacy.execute(sql);

    assertEquals(List.of(id), current.pending(100));
    new AccountLifecycleDispatcher(remote, current, mock(AccountLifecycleEffects.class), codec)
        .dispatch(id);

    assertEquals(
        "COMMITTED",
        legacy.queryForObject(
            "SELECT status FROM identity_lifecycle_attempt WHERE id=?",
            String.class,
            id.toString()));
    assertEquals(
        body,
        legacy.queryForObject(
            "SELECT body_json FROM identity_lifecycle_outbox WHERE id=?",
            String.class,
            outbox.toString()));
    assertEquals(
        Timestamp.from(NOW),
        legacy.queryForObject(
            "SELECT created_at FROM identity_lifecycle_outbox WHERE id=?",
            Timestamp.class,
            outbox.toString()));
  }

  private String status(UUID id) {
    return jdbc.queryForObject(
        "SELECT status FROM identity_lifecycle_attempt WHERE id=?", String.class, id.toString());
  }
}
