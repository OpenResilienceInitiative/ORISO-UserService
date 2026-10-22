package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ports the historical journal's independent begin and saga-wide row lock; adds immutable outbox.
 */
public final class JdbcAccountLifecycleJournal implements AccountLifecycleJournal {
  private final JdbcTemplate jdbc;
  private final AccountLifecycleCodec codec;
  private final TransactionTemplate independent;
  private final TransactionTemplate required;
  private final Clock clock;
  private final long leaseSeconds;

  public JdbcAccountLifecycleJournal(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      AccountLifecycleCodec codec,
      Clock clock,
      long leaseSeconds) {
    if (leaseSeconds < 60 || leaseSeconds > 3600)
      throw new IllegalArgumentException("Invalid creation lease");
    this.jdbc = jdbc;
    this.codec = codec;
    this.clock = clock;
    this.leaseSeconds = leaseSeconds;
    independent = new TransactionTemplate(transactions);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    required = new TransactionTemplate(transactions);
  }

  @Override
  public ExecutionRef begin(UUID id, CreationIdentity identity) {
    return separately(
        () -> {
          // A workflow is one original authorization. A second UUID must not create a second
          // account.
          String requestKey = codec.digest("local-creation-request", identity.scope());
          var existing =
              jdbc.queryForList(
                  "SELECT id,status FROM identity_lifecycle_attempt WHERE request_key=? FOR UPDATE",
                  requestKey);
          if (!existing.isEmpty()) {
            var previous = existing.getFirst();
            // Retry after UNKNOWN/COMMIT_REQUESTED must resume that attempt. A fully cleaned,
            // terminal compensation alone releases the checked workflow for a new attempt.
            if (!"COMPENSATED".equals(previous.get("status"))) throw denied();
            String retiredKey =
                codec.digest(
                    "completed-creation-request",
                    Map.of("requestKey", requestKey, "attemptId", previous.get("id")));
            if (jdbc.update(
                    "UPDATE identity_lifecycle_attempt SET request_key=? WHERE id=? AND request_key=? AND status='COMPENSATED'",
                    retiredKey,
                    previous.get("id"),
                    requestKey)
                != 1) throw denied();
          }
          UUID execution = UUID.randomUUID();
          jdbc.update(
              """
          INSERT INTO identity_lifecycle_attempt
          (id,request_key,identity_json,origin_kind,workflow_id,tenant_id,execution_id,original_execution_id,generation,status,journal_version,lease_until,update_date)
          VALUES (?,?,?,?,?,?,?,?,0,'CREATION_REQUESTED',1,?,?)
          """,
              id.toString(),
              requestKey,
              codec.canonical(identity),
              identity.scope().origin().kind(),
              identity.scope().origin().workflowId(),
              identity.scope().tenantId(),
              execution.toString(),
              execution.toString(),
              Timestamp.from(clock.instant().plusSeconds(leaseSeconds)),
              now());
          return new ExecutionRef(execution, 0);
        });
  }

  @Override
  public void created(
      UUID id, CreationIdentity identity, ExecutionRef execution, AttemptResult receipt) {
    separately(
        () -> {
          var row = lock(id);
          requireExecution(row, execution);
          if (!"CREATION_REQUESTED".equals(row.status())
              || !row.identity().equals(identity)
              || !id.equals(receipt.attemptId())
              || !execution.equals(receipt.execution())
              || !"OPEN".equals(receipt.state())) throw denied();
          jdbc.update(
              """
          UPDATE identity_lifecycle_attempt SET account_id=?,creation_proof=?,status='OPEN',
          journal_version=journal_version+1,lease_until=?,update_date=? WHERE id=?
          """,
              receipt.accountId(),
              receipt.creationProof(),
              Timestamp.from(clock.instant().plusSeconds(leaseSeconds)),
              now(),
              id.toString());
          return null;
        });
  }

  @Override
  public void createUncertain(UUID id, ExecutionRef execution) {
    separately(
        () -> {
          var row = lock(id);
          requireExecution(row, execution);
          if (!"CREATION_REQUESTED".equals(row.status())) throw denied();
          transition(id, "CREATE_UNCERTAIN");
          jdbc.update(
              "UPDATE identity_lifecycle_attempt SET lease_until=NULL WHERE id=?", id.toString());
          return null;
        });
  }

  @Override
  public <T> T inLocalSaga(UUID id, ExecutionRef execution, Function<Attempt, T> writes) {
    return required.execute(
        status -> {
          var row = lock(id);
          requireExecution(row, execution);
          var lease =
              jdbc.queryForObject(
                  "SELECT lease_until FROM identity_lifecycle_attempt WHERE id=?",
                  Timestamp.class,
                  id.toString());
          if (!"OPEN".equals(row.status())
              || lease == null
              || !lease.toInstant().isAfter(clock.instant())) throw denied();
          return AccountLifecycleExecution.within(
              row, jdbc.getDataSource(), () -> writes.apply(row));
        });
  }

  @Override
  public void prepareCommitInSaga(UUID id, ExecutionRef execution) {
    mandatory();
    var row = lock(id);
    requireExecution(row, execution);
    if (!"OPEN".equals(row.status()) || row.accountId() == null || row.creationProof() == null)
      throw denied();
    var outcome = outcome(row);
    var body =
        new CommitRequest(row.identity(), execution, row.accountId(), row.creationProof(), outcome);
    transition(id, "COMMIT_REQUESTED");
    insertIntent(row.id(), outcome.outboxId(), "COMMIT", codec.canonical(body));
  }

  @Override
  public void requestRecovery(UUID id, ExecutionRef execution) {
    separately(
        () -> {
          var row = lock(id);
          requireExecution(row, execution);
          if (Set.of("RECOVERY_REQUESTED", "LOCAL_CLEANUP_REQUESTED", "COMPENSATION_REQUESTED")
              .contains(row.status())) return null;
          if (!Set.of("CREATION_REQUESTED", "CREATE_UNCERTAIN", "OPEN").contains(row.status()))
            throw denied();
          requestRecoveryLocked(row);
          return null;
        });
  }

  /** Stale candidates are re-read under the very same lock held by every local saga. */
  public void claimExpired() {
    for (String candidate :
        jdbc.query(
            """
        SELECT id FROM identity_lifecycle_attempt
        WHERE status IN ('CREATION_REQUESTED','CREATE_UNCERTAIN','OPEN')
        AND (lease_until IS NULL OR lease_until<=?) ORDER BY update_date,id LIMIT 100
        """,
            (rs, i) -> rs.getString(1),
            now())) {
      UUID parsed = null;
      try {
        UUID id = UUID.fromString(candidate);
        if (!id.toString().equals(candidate)) throw denied();
        parsed = id;
        separately(
            () -> {
              var row = lock(id);
              var lease =
                  jdbc.queryForObject(
                      "SELECT lease_until FROM identity_lifecycle_attempt WHERE id=?",
                      Timestamp.class,
                      id.toString());
              if (Set.of("CREATION_REQUESTED", "CREATE_UNCERTAIN", "OPEN").contains(row.status())
                  && (lease == null || !lease.toInstant().isAfter(clock.instant())))
                requestRecoveryLocked(row);
              return null;
            });
      } catch (RuntimeException refused) {
        // A malformed private row cannot create an intent, abort later accounts, or own every
        // bounded page. Rotate only its scan timestamp after the failed transaction has rolled back.
        boolean rotated = false;
        try {
          rotated =
              separately(
                      () ->
                          jdbc.update(
                              "UPDATE identity_lifecycle_attempt SET update_date=? WHERE id=? AND status IN ('CREATION_REQUESTED','CREATE_UNCERTAIN','OPEN') AND (lease_until IS NULL OR lease_until<=?)",
                              now(),
                              candidate,
                              now()))
                  == 1;
        } catch (RuntimeException unavailable) {
          // No rollback/success is guessed when the independent fairness marker cannot commit.
        }
        org.slf4j.LoggerFactory.getLogger(JdbcAccountLifecycleJournal.class)
            .warn(
                "Expired lifecycle recovery refused; attempt={}, scanTimestampRecorded={}",
                parsed == null ? "INVALID_ID" : parsed,
                rotated);
      }
    }
  }

  private void requestRecoveryLocked(Attempt row) {
    UUID recoveryId = UUID.randomUUID();
    transition(row.id(), "RECOVERY_REQUESTED");
    insertIntent(
        row.id(),
        UUID.randomUUID(),
        "RECOVER",
        codec.canonical(
            new RecoverRequest(row.identity(), row.execution().generation(), recoveryId)));
  }

  @Override
  public Optional<Attempt> findWorkflow(
      String originKind, String workflowId, Long tenantId, String accountId) {
    if (originKind == null || workflowId == null || accountId == null) throw denied();
    String tenant = tenantId == null ? null : tenantId.toString();
    var rows =
        jdbc.query(
            "SELECT * FROM identity_lifecycle_attempt WHERE origin_kind=? AND workflow_id=? AND account_id=? AND (tenant_id=? OR (tenant_id IS NULL AND ? IS NULL)) ORDER BY update_date DESC LIMIT 2",
            this::attempt,
            originKind,
            workflowId,
            accountId,
            tenant,
            tenant);
    if (rows.size() > 1) throw denied();
    return rows.stream().findFirst();
  }

  @Override
  public void requireCommitted(UUID id) {
    String state =
        jdbc.queryForObject(
            "SELECT status FROM identity_lifecycle_attempt WHERE id=?",
            String.class,
            id.toString());
    if (!"COMMITTED".equals(state))
      throw new IllegalStateException("Account finalization is still pending");
  }

  @Override
  public List<UUID> pending(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid retry page");
    return separately(
        () -> {
          var due = now();
          var rows =
              jdbc.query(
                  """
                  SELECT id,attempt_id FROM identity_lifecycle_outbox
                  WHERE status='PENDING' AND next_attempt_at<=?
                  ORDER BY next_attempt_at,created_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                  """,
                  (rs, i) -> Map.entry(rs.getString(1), UUID.fromString(rs.getString(2))),
                  due,
                  limit);
          var retry = Timestamp.from(due.toInstant().plusSeconds(30));
          for (var row : rows) {
            if (jdbc.update(
                    "UPDATE identity_lifecycle_outbox SET next_attempt_at=? WHERE id=? AND status='PENDING' AND next_attempt_at<=?",
                    retry,
                    row.getKey(),
                    due)
                != 1) throw denied();
          }
          // This independent transaction commits every reservation before any remote dispatch.
          // Failure/rollback in withPending cannot restore a poison page's old queue position.
          return rows.stream().map(Map.Entry::getValue).toList();
        });
  }

  @Override
  public <T> T withPending(UUID id, Function<Pending, T> dispatch) {
    // afterCommit still has the completed transaction's resources bound. Dispatch must commit
    // its receipt in a separate transaction, including when called from another live workflow.
    return independent.execute(
        status -> {
          var row = lock(id);
          var outbox =
              jdbc.query(
                  """
          SELECT id,operation,body_json FROM identity_lifecycle_outbox
          WHERE attempt_id=? AND status='PENDING' ORDER BY created_at,id FOR UPDATE
          """,
                  (rs, i) ->
                      new Pending(
                          row, UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3)),
                  id.toString());
          if (outbox.isEmpty()) return null;
          if (outbox.size() != 1) throw denied();
          var pending = outbox.getFirst();
          String expected =
              switch (pending.operation()) {
                case "COMMIT" -> "COMMIT_REQUESTED";
                case "RECOVER" -> "RECOVERY_REQUESTED";
                case "CLEANUP" -> "LOCAL_CLEANUP_REQUESTED";
                case "COMPENSATE" -> "COMPENSATION_REQUESTED";
                default -> throw denied();
              };
          if (!expected.equals(row.status())) throw denied();
          return dispatch.apply(pending);
        });
  }

  @Override
  public void recovered(Pending pending, AttemptResult receipt) {
    mandatory();
    var row = lock(pending.attempt().id());
    var request = codec.read(pending.body(), RecoverRequest.class);
    if (!"RECOVERY_REQUESTED".equals(row.status())
        || !row.id().equals(receipt.attemptId())
        || !receipt.execution().executionId().equals(request.recoveryId())
        || receipt.execution().generation() != request.expectedGeneration() + 1) throw denied();
    if ("ABANDONED".equals(receipt.state()) && row.accountId() != null) {
      reconcile(pending);
      return;
    }
    if (row.accountId() != null
        && (!Objects.equals(row.accountId(), receipt.accountId())
            || !Objects.equals(row.creationProof(), receipt.creationProof()))) {
      reconcile(pending);
      return;
    }
    jdbc.update(
        """
        UPDATE identity_lifecycle_attempt SET execution_id=?,generation=?,account_id=?,creation_proof=?,
        recovery_receipt=?,status='LOCAL_CLEANUP_REQUESTED',journal_version=journal_version+1,update_date=? WHERE id=?
        """,
        receipt.execution().executionId().toString(),
        receipt.execution().generation(),
        receipt.accountId(),
        receipt.creationProof(),
        receipt.recoveryReceipt(),
        now(),
        row.id().toString());
    delivered(pending);
    insertIntent(row.id(), UUID.randomUUID(), "CLEANUP", "{}");
  }

  @Override
  public void cleaned(Pending pending) {
    mandatory();
    var row = lock(pending.attempt().id());
    if (!"LOCAL_CLEANUP_REQUESTED".equals(row.status())) throw denied();
    var remaining =
        jdbc.queryForObject(
            """
        SELECT COUNT(*) FROM identity_lifecycle_effect WHERE attempt_id=? AND state NOT IN ('NO_EFFECT','CLEANED')
        """,
            Integer.class,
            row.id().toString());
    if (remaining == null || remaining != 0) throw denied();
    var outcome = outcome(row);
    var request =
        new CompensateRequest(
            row.identity(), row.execution(), row.accountId(), row.creationProof(), outcome);
    transition(row.id(), "COMPENSATION_REQUESTED");
    delivered(pending);
    insertIntent(row.id(), outcome.outboxId(), "COMPENSATE", codec.canonical(request));
  }

  @Override
  public void finished(Pending pending, AttemptResult receipt) {
    mandatory();
    var row = lock(pending.attempt().id());
    if (!row.execution().equals(receipt.execution())
        || !row.id().equals(receipt.attemptId())
        || !Objects.equals(row.accountId(), receipt.accountId())
        || !Objects.equals(row.creationProof(), receipt.creationProof())) throw denied();
    String terminal =
        switch (pending.operation()) {
          case "COMMIT" -> {
            if (!"COMMITTED".equals(receipt.state())) throw denied();
            yield "COMMITTED";
          }
          case "COMPENSATE" -> {
            if (!Set.of("COMPENSATED", "ABANDONED").contains(receipt.state())) throw denied();
            yield "COMPENSATED";
          }
          default -> throw denied();
        };
    jdbc.update(
        """
        UPDATE identity_lifecycle_attempt SET terminal_receipt=?,status=?,journal_version=journal_version+1,
        lease_until=NULL,update_date=? WHERE id=?
        """,
        receipt.terminalReceipt(),
        terminal,
        now(),
        row.id().toString());
    delivered(pending);
  }

  @Override
  public void reconcile(Pending pending) {
    mandatory();
    transition(pending.attempt().id(), "LOCAL_RECONCILIATION_REQUIRED");
    delivered(pending);
  }

  private LocalOutcome outcome(Attempt row) {
    var inventory =
        jdbc.queryForList(
            """
        SELECT id,attempt_id,account_id,tenant_id,execution_id,effect_kind,requested_target,target_id,provenance,state
        FROM identity_lifecycle_effect WHERE attempt_id=? ORDER BY id FOR UPDATE
        """,
            row.id().toString());
    if (inventory.stream()
        .anyMatch(e -> !Set.of("ACKNOWLEDGED", "NO_EFFECT", "CLEANED").contains(e.get("state"))))
      throw denied();
    return new LocalOutcome(
        row.version() + 1, UUID.randomUUID(), codec.digest("local-effects", inventory));
  }

  private Attempt lock(UUID id) {
    mandatory();
    var rows =
        jdbc.query(
            "SELECT * FROM identity_lifecycle_attempt WHERE id=? FOR UPDATE",
            this::attempt,
            id.toString());
    if (rows.size() != 1) throw denied();
    return rows.getFirst();
  }

  private Attempt attempt(ResultSet rs, int index) throws SQLException {
    return new Attempt(
        UUID.fromString(rs.getString("id")),
        codec.read(rs.getString("identity_json"), CreationIdentity.class),
        new ExecutionRef(UUID.fromString(rs.getString("execution_id")), rs.getLong("generation")),
        UUID.fromString(rs.getString("original_execution_id")),
        rs.getString("account_id"),
        rs.getString("creation_proof"),
        rs.getString("status"),
        rs.getLong("journal_version"));
  }

  private void transition(UUID id, String state) {
    jdbc.update(
        "UPDATE identity_lifecycle_attempt SET status=?,journal_version=journal_version+1,update_date=? WHERE id=?",
        state,
        now(),
        id.toString());
  }

  private void insertIntent(UUID id, UUID outbox, String operation, String body) {
    jdbc.update(
        "INSERT INTO identity_lifecycle_outbox(id,attempt_id,operation,body_json,status,created_at,next_attempt_at) VALUES (?,?,?,?,'PENDING',?,?)",
        outbox.toString(),
        id.toString(),
        operation,
        body,
        now(),
        now());
  }

  private void delivered(Pending pending) {
    int changed =
        jdbc.update(
            "UPDATE identity_lifecycle_outbox SET status='DELIVERED' WHERE id=? AND attempt_id=? AND status='PENDING'",
            pending.outboxId().toString(),
            pending.attempt().id().toString());
    if (changed != 1) throw denied();
  }

  private <T> T separately(Supplier<T> work) {
    return independent.execute(status -> work.get());
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private static void requireExecution(Attempt row, ExecutionRef execution) {
    if (!row.execution().equals(execution)) throw denied();
  }

  private static void mandatory() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Lifecycle journal requires transaction");
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Creation lifecycle exceeds recorded execution");
  }
}
