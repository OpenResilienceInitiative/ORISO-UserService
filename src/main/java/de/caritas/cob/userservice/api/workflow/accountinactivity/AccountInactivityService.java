package de.caritas.cob.userservice.api.workflow.accountinactivity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountInactivityService {
  public enum Status {
    ACTIVE,
    SUSPENDING,
    SUSPENDED,
    DELETING,
    DELETED,
    REACTIVATING
  }

  public record Snapshot(
      String identityId,
      Long tenantId,
      int assignedMonths,
      long revision,
      Instant lastActivity,
      Instant dueAt,
      Status status,
      int attempts,
      String lastError) {}

  public record Attempt(Instant at, String action, String target, String outcome) {}

  public enum PlannedAction {
    DELETE,
    SUSPEND,
    REACTIVATE,
    BLOCKED
  }

  public record Candidate(
      Snapshot snapshot,
      java.util.Set<AccountInactivityEffects.Role> roles,
      PlannedAction plannedAction) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final TransactionTemplate work;
  private final Clock clock;
  private final AccountInactivityEffects effects;

  public AccountInactivityService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock,
      AccountInactivityEffects effects) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.work = new TransactionTemplate(transactionManager);
    this.work.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.effects = effects;
  }

  public void assignAtCreation(
      String identityId, Long tenantId, int months, long revision, Instant createdAt) {
    if (months < 1) throw new IllegalArgumentException("Months must be positive");
    if (snapshot(identityId).isPresent()) return;
    try {
      jdbc.update(
          "INSERT INTO"
              + " account_inactivity(identity_id,tenant_id,assigned_months,revision,last_activity,due_at,status)"
              + " VALUES(?,?,?,?,?,?,?)",
          identityId,
          tenantId,
          months,
          revision,
          utc(createdAt),
          utc(due(createdAt, months)),
          Status.ACTIVE.name());
    } catch (org.springframework.dao.DuplicateKeyException concurrentCreation) {
      // The only unique key is identity_id. A concurrent winner owns the immutable snapshot.
    }
  }

  /** Removes only the untouched snapshot owned by a failed account-provisioning attempt. */
  public boolean discardUncompletedCreation(
      String identityId, int assignedMonths, long revision, Instant capturedAt) {
    return jdbc.update(
            "DELETE FROM account_inactivity WHERE identity_id=? AND status='ACTIVE' AND attempts=0"
                + " AND assigned_months=? AND revision=? AND last_activity=? AND due_at=?",
            identityId,
            assignedMonths,
            revision,
            utc(capturedAt),
            utc(due(capturedAt, assignedMonths)))
        == 1;
  }

  public Optional<Snapshot> snapshot(String id) {
    return jdbc
        .query("SELECT * FROM account_inactivity WHERE identity_id=?", this::map, id)
        .stream()
        .findFirst();
  }

  public void recordActivity(String id, Instant activityAt) {
    admit(id, activityAt);
  }

  /**
   * Atomically admits an active identity and optionally records a verified login or deliberate use.
   */
  public boolean admit(String id, Instant confirmedLoginAt) {
    Instant now = clock.instant();
    Instant observed =
        confirmedLoginAt == null ? null : (confirmedLoginAt.isAfter(now) ? now : confirmedLoginAt);
    return Boolean.TRUE.equals(
        tx.execute(
            ignored -> {
              var existing = locked(id);
              if (existing.isEmpty() || existing.get().status() != Status.ACTIVE) return false;
              var row = existing.get();
              if (observed != null && observed.isAfter(row.lastActivity())) {
                jdbc.update(
                    "UPDATE account_inactivity SET last_activity=?,due_at=? WHERE identity_id=?",
                    utc(observed),
                    utc(due(observed, row.assignedMonths())),
                    id);
              }
              return true;
            }));
  }

  /** Fences application-controlled realm-role changes against lifecycle claims. */
  public void withRoleMutation(String id, Runnable change) {
    tx.executeWithoutResult(
        ignored -> {
          var existing = locked(id);
          if (existing.isPresent() && existing.get().status() != Status.ACTIVE)
            throw new IllegalStateException("Account lifecycle action prevents role changes");
          change.run();
        });
  }

  public List<Snapshot> candidates(String afterIdentityId, int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Page size must be 1..1000");
    return jdbc.query(
        "SELECT * FROM account_inactivity WHERE identity_id>? AND ((status='ACTIVE' AND due_at<=?)"
            + " OR status IN ('DELETING','SUSPENDING','REACTIVATING')) ORDER BY identity_id LIMIT"
            + " ?",
        this::map,
        afterIdentityId == null ? "" : afterIdentityId,
        utc(clock.instant()),
        limit);
  }

  public List<Candidate> candidateReport(String afterIdentityId, int limit) {
    return candidates(afterIdentityId, limit).stream()
        .map(
            snapshot -> {
              java.util.Set<AccountInactivityEffects.Role> roles;
              try {
                roles = effects.currentRoles(snapshot.identityId());
              } catch (RuntimeException unavailable) {
                roles = java.util.Set.of(AccountInactivityEffects.Role.UNKNOWN);
              }
              if (roles == null || roles.isEmpty())
                roles = java.util.Set.of(AccountInactivityEffects.Role.UNKNOWN);
              PlannedAction action;
              if (snapshot.status() == Status.REACTIVATING) action = PlannedAction.REACTIVATE;
              else if (snapshot.status() == Status.SUSPENDING) action = PlannedAction.SUSPEND;
              else if (roles.contains(AccountInactivityEffects.Role.UNKNOWN))
                action = PlannedAction.BLOCKED;
              else if (roles.equals(java.util.Set.of(AccountInactivityEffects.Role.ASKER)))
                action = PlannedAction.DELETE;
              else action = PlannedAction.SUSPEND;
              return new Candidate(snapshot, java.util.Set.copyOf(roles), action);
            })
        .toList();
  }

  public void scan(boolean dryRun) {
    String cursor = "";
    while (true) {
      var page = candidates(cursor, 200);
      if (page.isEmpty()) return;
      for (var candidate : page) {
        if (!dryRun) {
          try {
            claim(candidate.identityId());
            execute(candidate.identityId());
          } catch (RuntimeException failure) {
            // A claimed action records its typed failure in execute(); role-discovery failures
            // keep the account active and are retried on the next scan.
            if (snapshot(candidate.identityId())
                .map(row -> row.status() == Status.ACTIVE)
                .orElse(false)) {
              jdbc.update(
                  "UPDATE account_inactivity SET last_error=? WHERE identity_id=?",
                  "ROLE_DISCOVERY_FAILED",
                  candidate.identityId());
              audit(candidate.identityId(), "ACTIVE", "KEYCLOAK", "ROLE_DISCOVERY_FAILED");
            }
          }
        }
      }
      cursor = page.getLast().identityId();
    }
  }

  private void claim(String id) {
    work.executeWithoutResult(
        ignored -> {
          var row = locked(id).orElseThrow();
          if (row.status() != Status.ACTIVE || row.dueAt().isAfter(clock.instant())) return;
          var roles = effects.currentRoles(id);
          if (roles == null
              || roles.isEmpty()
              || roles.contains(AccountInactivityEffects.Role.UNKNOWN))
            throw new IllegalStateException("Current roles unavailable");
          var status =
              roles.equals(java.util.Set.of(AccountInactivityEffects.Role.ASKER))
                  ? Status.DELETING
                  : Status.SUSPENDING;
          jdbc.update(
              "UPDATE account_inactivity SET status=?,last_error=NULL WHERE identity_id=?",
              status.name(),
              id);
          audit(id, status.name(), "DATABASE", "CLAIMED");
        });
  }

  /** Caller must enforce platform-administrator authorization. */
  public boolean suspend(String id) {
    work.executeWithoutResult(
        ignored -> {
          var row = locked(id).orElseThrow();
          if (row.status() == Status.SUSPENDING || row.status() == Status.SUSPENDED) return;
          if (row.status() != Status.ACTIVE)
            throw new IllegalStateException("Account has another lifecycle action");
          jdbc.update("UPDATE account_inactivity SET status='SUSPENDING' WHERE identity_id=?", id);
          audit(id, "SUSPENDING", "DATABASE", "CLAIMED");
        });
    if (snapshot(id).orElseThrow().status() == Status.SUSPENDED) return true;
    return execute(id);
  }

  /** Caller must enforce platform-administrator authorization. */
  public boolean reactivate(String id) {
    work.executeWithoutResult(
        ignored -> {
          var row = locked(id).orElseThrow();
          if (row.status() == Status.REACTIVATING || row.status() == Status.ACTIVE) return;
          if (row.status() != Status.SUSPENDED)
            throw new IllegalStateException("Only suspended accounts can reactivate");
          jdbc.update(
              "UPDATE account_inactivity SET status='REACTIVATING' WHERE identity_id=?", id);
          audit(id, "REACTIVATING", "DATABASE", "CLAIMED");
        });
    if (snapshot(id).orElseThrow().status() == Status.ACTIVE) return true;
    return execute(id);
  }

  public List<Attempt> journal(String id) {
    return jdbc.query(
        "SELECT * FROM account_inactivity_journal WHERE identity_id=? ORDER BY journal_id LIMIT"
            + " 1000",
        (r, n) ->
            new Attempt(
                r.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                r.getString("action"),
                r.getString("target"),
                r.getString("outcome")),
        id);
  }

  private void audit(String id, String action, String target, String outcome) {
    jdbc.update(
        "INSERT INTO account_inactivity_journal(identity_id,action,target,outcome,created_at)"
            + " VALUES(?,?,?,?,?)",
        id,
        action,
        target,
        outcome,
        utc(clock.instant()));
  }

  private boolean execute(String id) {
    try {
      return executeLocked(id);
    } catch (RuntimeException error) {
      work.executeWithoutResult(
          ignored -> {
            var row = locked(id).orElseThrow();
            var target =
                error instanceof AccountInactivityEffectException e ? e.target().name() : "OTHER";
            var code =
                error instanceof AccountInactivityEffectException e ? e.code().name() : "FAILED";
            jdbc.update(
                "UPDATE account_inactivity SET attempts=attempts+1,last_error=? WHERE"
                    + " identity_id=?",
                target + ":" + code,
                id);
            audit(id, row.status().name(), target, code);
            if (row.status() == Status.DELETING
                && error instanceof AccountInactivityEffectException changed
                && changed.code() == AccountInactivityEffectException.Code.ROLE_CHANGED) {
              jdbc.update(
                  "UPDATE account_inactivity SET status='SUSPENDING' WHERE identity_id=?", id);
              audit(id, "SUSPENDING", "DATABASE", "CLAIMED");
            }
          });
      throw error;
    }
  }

  private boolean executeLocked(String id) {
    return Boolean.TRUE.equals(
        work.execute(
            ignored -> {
              var row = locked(id).orElseThrow();
              boolean complete;
              Status next;
              switch (row.status()) {
                case DELETING -> {
                  complete = effects.delete(id);
                  next = Status.DELETED;
                }
                case SUSPENDING -> {
                  complete = effects.suspend(id);
                  next = Status.SUSPENDED;
                }
                case REACTIVATING -> {
                  complete = effects.reactivate(id);
                  next = Status.ACTIVE;
                }
                default -> {
                  return false;
                }
              }
              jdbc.update(
                  "UPDATE account_inactivity SET attempts=attempts+1,last_error=? WHERE"
                      + " identity_id=?",
                  complete ? null : "External effects incomplete",
                  id);
              audit(id, row.status().name(), "ALL", complete ? "CONFIRMED" : "UNCONFIRMED");
              if (complete) {
                jdbc.update(
                    "UPDATE account_inactivity SET status=? WHERE identity_id=?", next.name(), id);
                if (next == Status.ACTIVE)
                  jdbc.update(
                      "UPDATE account_inactivity SET last_activity=?,due_at=? WHERE identity_id=?",
                      utc(clock.instant()),
                      utc(due(clock.instant(), row.assignedMonths())),
                      id);
              }
              return complete;
            }));
  }

  private Optional<Snapshot> locked(String id) {
    return jdbc
        .query("SELECT * FROM account_inactivity WHERE identity_id=? FOR UPDATE", this::map, id)
        .stream()
        .findFirst();
  }

  private LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private Instant due(Instant last, int months) {
    var calculated = last.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    var databaseMaximum = Instant.parse("9999-12-31T23:59:59Z");
    return calculated.isAfter(databaseMaximum) ? databaseMaximum : calculated;
  }

  private Snapshot map(ResultSet r, int row) throws SQLException {
    return new Snapshot(
        r.getString("identity_id"),
        r.getObject("tenant_id", Long.class),
        r.getInt("assigned_months"),
        r.getLong("revision"),
        r.getObject("last_activity", LocalDateTime.class).toInstant(ZoneOffset.UTC),
        r.getObject("due_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
        Status.valueOf(r.getString("status")),
        r.getInt("attempts"),
        r.getString("last_error"));
  }
}
