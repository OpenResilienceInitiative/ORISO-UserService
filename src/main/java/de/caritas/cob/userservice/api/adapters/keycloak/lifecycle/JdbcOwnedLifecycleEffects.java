package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ports the historical independent child inventory without unsafe username/tenant-wide searches.
 */
public final class JdbcOwnedLifecycleEffects implements AccountLifecycleEffects {
  public enum Kind {
    MATRIX_USER,
    PRIVATE_ROOM,
    APPOINTMENT_CONSULTANT,
    LOCAL_USER,
    LOCAL_SESSION,
    LOCAL_AGENCY_RELATION
  }

  public record OwnedEffect(
      UUID id,
      UUID attemptId,
      String accountId,
      String tenantId,
      UUID originalExecution,
      Kind kind,
      String requestedTarget,
      String targetId,
      String provenance,
      String state) {
    @Override
    public String toString() {
      return "OwnedEffect[kind=" + kind + ",target=redacted]";
    }
  }

  /**
   * Implementations perform idempotent exact-reference cleanup; no creator credential/general
   * deletion.
   */
  public interface CleanupOperations {
    void clean(OwnedEffect effect);
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate independent;
  private final CleanupOperations cleanup;
  private final AccountLifecycleCodec codec;

  public JdbcOwnedLifecycleEffects(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      CleanupOperations cleanup,
      AccountLifecycleCodec codec) {
    this.jdbc = jdbc;
    this.cleanup = cleanup;
    this.codec = codec;
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Scope capture(AccountLifecycleJournal.Attempt attempt) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !"OPEN".equals(attempt.status())
        || attempt.accountId() == null
        || !attempt.execution().executionId().equals(attempt.originalExecution())) throw denied();
    requireCurrentSaga(attempt);
    return new Scope(attempt);
  }

  /** A scope comes from the actual locked caller journal row, never an arbitrary target search. */
  public final class Scope {
    private final AccountLifecycleJournal.Attempt attempt;

    private Scope(AccountLifecycleJournal.Attempt attempt) {
      this.attempt = attempt;
    }

    public void requireOwner(String accountId, Long actualTenant) {
      if (!Objects.equals(attempt.accountId(), accountId)
          || !Objects.equals(
              attempt.identity().scope().tenantId(),
              actualTenant == null ? null : actualTenant.toString())) throw denied();
    }

    public de.caritas.cob.userservice.api.port.out.OwnedMatrixEffect privateRoom() {
      return new de.caritas.cob.userservice.api.port.out.OwnedMatrixEffect() {
        private UUID effectId;

        public void started(String requestedTarget) {
          if (effectId != null) throw denied();
          effectId =
              JdbcOwnedLifecycleEffects.this.started(attempt, Kind.PRIVATE_ROOM, requestedTarget);
        }

        public void created(String exactTarget) {
          if (effectId == null) throw denied();
          acknowledged(effectId, exactTarget);
        }

        public void restoreDeactivated(String target) {
          throw denied();
        }

        public void rejectedWithoutEffect() {
          if (effectId == null) throw denied();
          JdbcOwnedLifecycleEffects.this.rejectedWithoutEffect(effectId);
        }
      };
    }
  }

  /** Called only within the original locked local saga. STARTED survives its possible rollback. */
  public UUID started(AccountLifecycleJournal.Attempt attempt, Kind kind, String requestedTarget) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !"OPEN".equals(attempt.status())
        || attempt.accountId() == null
        || !attempt.execution().executionId().equals(attempt.originalExecution())
        || requestedTarget == null
        || requestedTarget.isBlank()
        || requestedTarget.length() > 512) throw denied();
    requireCurrentSaga(attempt);
    UUID id = UUID.randomUUID();
    separately(
        () -> {
          jdbc.update(
              """
          INSERT INTO identity_lifecycle_effect
          (id,attempt_id,account_id,tenant_id,execution_id,effect_kind,requested_target,state)
          VALUES (?,?,?,?,?,?,?,'STARTED')
          """,
              id.toString(),
              attempt.id().toString(),
              attempt.accountId(),
              attempt.identity().scope().tenantId(),
              attempt.originalExecution().toString(),
              kind.name(),
              requestedTarget);
          return null;
        });
    return id;
  }

  /** Only an acknowledged create of an exact returned target confers deletion ownership. */
  public void acknowledged(UUID id, String actualTarget) {
    separately(
        () -> {
          var row = lock(id);
          if (!"STARTED".equals(row.state())
              || actualTarget == null
              || actualTarget.isBlank()
              || actualTarget.length() > 512) throw denied();
          if (row.kind() != Kind.PRIVATE_ROOM && !actualTarget.equals(row.requestedTarget()))
            throw denied();
          if (row.kind() == Kind.APPOINTMENT_CONSULTANT && !actualTarget.equals(row.accountId()))
            throw denied();
          jdbc.update(
              "UPDATE identity_lifecycle_effect SET target_id=?,provenance='CREATED',state='ACKNOWLEDGED' WHERE id=? AND state='STARTED'",
              actualTarget,
              id.toString());
          return null;
        });
  }

  /**
   * Use only for a definitive rejection before mutation, never a timeout or ambiguous provider
   * error.
   */
  public void rejectedWithoutEffect(UUID id) {
    separately(
        () -> {
          var row = lock(id);
          if (!"STARTED".equals(row.state())) throw denied();
          jdbc.update(
              "UPDATE identity_lifecycle_effect SET state='NO_EFFECT' WHERE id=?", id.toString());
          return null;
        });
  }

  @Override
  public void clean(AccountLifecycleJournal.Attempt attempt) {
    if (!"LOCAL_CLEANUP_REQUESTED".equals(attempt.status())) throw denied();
    // The dispatcher holds the journal lock. No new saga effects can begin after receiver fencing.
    var rows =
        jdbc.query(
            """
        SELECT * FROM identity_lifecycle_effect WHERE attempt_id=?
        ORDER BY CASE WHEN effect_kind='PRIVATE_ROOM' THEN 0 WHEN effect_kind='MATRIX_USER' THEN 2 ELSE 1 END,id
        """,
            this::read,
            attempt.id().toString());
    for (var effect : rows) {
      sameOwner(attempt, effect);
      if (Set.of("NO_EFFECT", "CLEANED").contains(effect.state())) continue;
      if (!"ACKNOWLEDGED".equals(effect.state())
          || !"CREATED".equals(effect.provenance())
          || effect.targetId() == null)
        throw new IllegalStateException("Uncertain owned effect requires reconciliation");
      cleanup.clean(
          effect); // must fail on unacknowledged deletion; never suppress provider failure
      separately(
          () -> {
            var locked = lock(effect.id());
            sameOwner(attempt, locked);
            if (!locked.equals(effect)) throw denied();
            int changed =
                jdbc.update(
                    "UPDATE identity_lifecycle_effect SET state='CLEANED' WHERE id=? AND state='ACKNOWLEDGED'",
                    effect.id().toString());
            if (changed != 1) throw denied();
            return null;
          });
    }
  }

  private void requireCurrentSaga(AccountLifecycleJournal.Attempt attempt) {
    // This is the OUTER transaction's connection, before suspending it for a child-only write.
    // Re-reading with its parent lock fences stale scopes reused in a different transaction.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
    AccountLifecycleExecution.require(attempt, jdbc.getDataSource());
    var rows =
        jdbc.queryForList(
            "SELECT status,account_id,identity_json,execution_id,original_execution_id,generation,lease_until FROM identity_lifecycle_attempt WHERE id=? FOR UPDATE",
            attempt.id().toString());
    if (rows.size() != 1) throw denied();
    var row = rows.getFirst();
    if (!"OPEN".equals(row.get("status"))
        || !Objects.equals(attempt.accountId(), row.get("account_id"))
        || !Objects.equals(codec.canonical(attempt.identity()), row.get("identity_json"))
        || !Objects.equals(attempt.execution().executionId().toString(), row.get("execution_id"))
        || !Objects.equals(attempt.originalExecution().toString(), row.get("original_execution_id"))
        || ((Number) row.get("generation")).longValue() != attempt.execution().generation())
      throw denied();
  }

  private OwnedEffect lock(UUID id) {
    var rows =
        jdbc.query(
            "SELECT * FROM identity_lifecycle_effect WHERE id=? FOR UPDATE",
            this::read,
            id.toString());
    if (rows.size() != 1) throw denied();
    return rows.getFirst();
  }

  private OwnedEffect read(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
    return new OwnedEffect(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("attempt_id")),
        rs.getString("account_id"),
        rs.getString("tenant_id"),
        UUID.fromString(rs.getString("execution_id")),
        Kind.valueOf(rs.getString("effect_kind")),
        rs.getString("requested_target"),
        rs.getString("target_id"),
        rs.getString("provenance"),
        rs.getString("state"));
  }

  private static void sameOwner(AccountLifecycleJournal.Attempt attempt, OwnedEffect effect) {
    if (!attempt.id().equals(effect.attemptId())
        || !Objects.equals(attempt.accountId(), effect.accountId())
        || !Objects.equals(attempt.identity().scope().tenantId(), effect.tenantId())
        || !attempt.originalExecution().equals(effect.originalExecution())) throw denied();
  }

  private <T> T separately(Supplier<T> work) {
    return independent.execute(status -> work.get());
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Unproven owned creation effect");
  }
}
