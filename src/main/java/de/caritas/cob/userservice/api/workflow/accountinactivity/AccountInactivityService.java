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

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;
  private final InactivityClaimJournal claimJournal;
  private de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport
      scopedTransport;
  private de.caritas.cob.userservice.api.helper.AuthenticatedUser scopedHuman;
  private jakarta.servlet.http.HttpServletRequest scopedRequest;
  private String scopedIssuer;
  private java.util.Set<String> scopedClients = java.util.Set.of();

  @org.springframework.beans.factory.annotation.Autowired
  public void setIdentityMode(de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode) {
    this.identityMode = java.util.Objects.requireNonNull(mode);
  }

  public AccountInactivityService(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock,
      AccountInactivityEffects effects) {
    this.jdbc = jdbc;
    this.claimJournal = new InactivityClaimJournal(jdbc);
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
                if (scopedMode()) {
                  roles = java.util.Set.of(AccountInactivityEffects.Role.UNKNOWN);
                  if (java.util.Set.of(Status.SUSPENDING, Status.DELETING, Status.REACTIVATING)
                      .contains(snapshot.status())) {
                    var saved = claimJournal.saved(snapshot.identityId(), false);
                    if (saved.work() != null
                        && "SCHEDULED"
                            .equals(
                                claimJournal
                                    .decode(saved.claimed())
                                    .path("initiator")
                                    .path("kind")
                                    .textValue()))
                      try (var checked = new ClaimedWork(saved, null)) {
                        roles = effects.currentRoles(checked);
                      }
                  }
                } else roles = effects.currentRoles(snapshot.identityId());
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
    if (scopedMode()) {
      if (!dryRun) scopedScan();
      return;
    }
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
    if (scopedMode()) return scopedManual(id, false);
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
    if (scopedMode()) return scopedManual(id, true);
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

  private boolean scopedMode() {
    return identityMode != null && identityMode.isFiveActorMode();
  }

  /** Root supplies the same configured public issuer/clients as every other checked human path. */
  public void configureScopedInactivity(
      de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport transport,
      de.caritas.cob.userservice.api.helper.AuthenticatedUser human,
      jakarta.servlet.http.HttpServletRequest request,
      String issuer,
      java.util.Set<String> clients) {
    this.scopedTransport = java.util.Objects.requireNonNull(transport);
    this.scopedHuman = java.util.Objects.requireNonNull(human);
    this.scopedRequest = java.util.Objects.requireNonNull(request);
    this.scopedIssuer = java.util.Objects.requireNonNull(issuer);
    this.scopedClients = java.util.Set.copyOf(clients);
  }

  private record ManualHuman(
      String subject, String issuer, String client, String bearer, Instant expires) {
    java.util.Map<String, Object> binding() {
      return java.util.Map.of(
          "kind", "HUMAN_ADMIN", "subject", subject, "issuer", issuer, "client", client);
    }

    @Override
    public String toString() {
      return "ManualHuman[authorization=redacted]";
    }
  }

  private ManualHuman currentPlatformHuman() {
    if (scopedHuman == null
        || scopedRequest == null
        || scopedIssuer == null
        || !scopedHuman.isPlatformAdmin()
        || !(scopedRequest.getUserPrincipal()
            instanceof
            org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken
                    auth)
        || !auth.isAuthenticated()) throw InactivityClaimJournal.invalid();
    var jwt = auth.getToken();
    String subject = jwt.getSubject(), client = jwt.getClaimAsString("azp");
    String bearer = scopedRequest.getHeader("Authorization");
    if (subject == null
        || !java.util.UUID.fromString(subject).toString().equals(subject)
        || !subject.equals(scopedHuman.getUserId())
        || jwt.getIssuer() == null
        || !scopedIssuer.equals(jwt.getIssuer().toString())
        || !scopedClients.contains(client)
        || jwt.getExpiresAt() == null
        || !clock.instant().isBefore(jwt.getExpiresAt())
        || bearer == null
        || !bearer.equals("Bearer " + jwt.getTokenValue())) throw InactivityClaimJournal.invalid();
    return new ManualHuman(subject, scopedIssuer, client, bearer, jwt.getExpiresAt());
  }

  private void outsideSql() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw InactivityClaimJournal.invalid();
  }

  private void scopedScan() {
    outsideSql();
    String cursor = "";
    while (true) {
      var page = candidates(cursor, 200);
      if (page.isEmpty()) return;
      for (var candidate : page) {
        ClaimedWork checked = null;
        try {
          checked = work.execute(ignored -> scheduledClaim(candidate.identityId()));
          if (checked == null) continue;
          if (checked.terminalOnly) {
            if (scopedTransport == null) throw InactivityClaimJournal.invalid();
            scopedTransport.verifyNoEffect(
                checked,
                new de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport
                    .Frozen(checked.saved.body()),
                checked.saved.receipt());
            var previous = checked;
            checked =
                work.execute(
                    ignored -> {
                      previous.requireOwned(true);
                      return new ClaimedWork(
                          claimJournal.claim(
                              locked(previous.identityId()).orElseThrow(),
                              "SUSPEND",
                              java.util.Map.of("kind", "SCHEDULED"),
                              clock.instant(),
                              null),
                          null);
                    });
            previous.close();
          }
          if (checked.action().equals("SUSPEND") && checked.saved.phase().equals("CLAIMED")) {
            var roles = effects.currentRoles(checked);
            if (roles == null
                || roles.isEmpty()
                || roles.contains(AccountInactivityEffects.Role.UNKNOWN))
              throw new AccountInactivityEffectException(
                  AccountInactivityEffectException.Target.KEYCLOAK,
                  AccountInactivityEffectException.Code.UNCONFIRMED);
            if (roles.equals(java.util.Set.of(AccountInactivityEffects.Role.ASKER))) {
              var previous = checked;
              checked =
                  work.execute(
                      ignored -> {
                        previous.requireOwned(true);
                        var row = locked(previous.identityId()).orElseThrow();
                        var saved =
                            claimJournal.claim(
                                row,
                                "DELETE",
                                java.util.Map.of("kind", "SCHEDULED"),
                                clock.instant(),
                                null);
                        return new ClaimedWork(saved, null);
                      });
              previous.close();
            }
          }
          executeScoped(checked);
        } catch (RuntimeException failure) {
          if (checked != null) recordScopedFailure(checked, failure);
        } finally {
          if (checked != null) checked.close();
        }
      }
      cursor = page.getLast().identityId();
    }
  }

  private ClaimedWork scheduledClaim(String id) {
    var row = locked(id).orElse(null);
    if (row == null) return null;
    if (row.status() == Status.ACTIVE) {
      if (row.dueAt().isAfter(clock.instant())) return null;
      var prior = claimJournal.saved(id, true);
      if (prior.work() != null && "NO_EFFECT".equals(prior.phase()))
        return new ClaimedWork(prior, null, true);
      // The same row lock interlocks all role writers while remote work runs outside SQL.
      if (!jdbc.queryForList(
              "select operation_id from general_admin_identity_intent where account_id=? and status<>'APPLIED' for update",
              String.class,
              id)
          .isEmpty()) return null;
      var saved =
          claimJournal.claim(
              row, "SUSPEND", java.util.Map.of("kind", "SCHEDULED"), clock.instant(), null);
      audit(id, "SUSPENDING", "DATABASE", "CLAIMED");
      return new ClaimedWork(saved, null);
    }
    if (!java.util.Set.of(Status.SUSPENDING, Status.DELETING, Status.REACTIVATING)
        .contains(row.status())) return null;
    var saved = claimJournal.saved(id, true);
    if (saved.work() == null
        || !"SCHEDULED"
            .equals(
                claimJournal.decode(saved.claimed()).path("initiator").path("kind").textValue()))
      return null;
    var checked = new ClaimedWork(saved, null);
    checked.requireOwned(true);
    return checked;
  }

  private boolean scopedManual(String id, boolean restoring) {
    outsideSql();
    ManualHuman human = currentPlatformHuman();
    InactivityClaimJournal.Saved verifiedTerminal = null;
    var existingRow = snapshot(id).orElseThrow();
    if (java.util.Set.of(Status.ACTIVE, Status.SUSPENDED).contains(existingRow.status())) {
      var prior = claimJournal.saved(id, false);
      if (prior.work() != null && "NO_EFFECT".equals(prior.phase())) {
        if (scopedTransport == null) throw InactivityClaimJournal.invalid();
        try (var terminal = new ClaimedWork(prior, human, true)) {
          scopedTransport.verifyNoEffect(
              terminal,
              new de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.Frozen(
                  prior.body()),
              prior.receipt());
          terminal.requireCurrent();
          verifiedTerminal = prior;
        }
      }
    }
    final var terminalProof = verifiedTerminal;
    var checked =
        work.execute(
            ignored -> {
              var row = locked(id).orElseThrow();
              var before = claimJournal.saved(id, true);
              if (before.work() != null
                  && "NO_EFFECT".equals(before.phase())
                  && !before.equals(terminalProof)) throw InactivityClaimJournal.invalid();
              if (!restoring && row.status() == Status.SUSPENDED
                  || restoring && row.status() == Status.ACTIVE) return null;
              if (row.status() == Status.SUSPENDING || row.status() == Status.REACTIVATING) {
                var saved = claimJournal.saved(id, true);
                var existing = new ClaimedWork(saved, human);
                existing.requireOwned(true);
                if (!existing.action().equals(restoring ? "RESTORE" : "SUSPEND"))
                  throw InactivityClaimJournal.invalid();
                return existing;
              }
              if (restoring) {
                if (row.status() != Status.SUSPENDED) throw InactivityClaimJournal.invalid();
                var prior = claimJournal.saved(id, true);
                var proof =
                    jdbc.queryForMap(
                        "select suspension_body,suspension_receipt from account_inactivity where identity_id=?",
                        id);
                if (prior.suspension() == null
                    || proof.get("suspension_body") == null
                    || proof.get("suspension_receipt") == null)
                  throw InactivityClaimJournal.invalid();
                return new ClaimedWork(
                    claimJournal.claim(
                        row, "RESTORE", human.binding(), clock.instant(), prior.suspension()),
                    human);
              }
              if (row.status() != Status.ACTIVE) throw InactivityClaimJournal.invalid();
              if (!jdbc.queryForList(
                      "select operation_id from general_admin_identity_intent where account_id=? and status<>'APPLIED' for update",
                      String.class,
                      id)
                  .isEmpty()) throw InactivityClaimJournal.invalid();
              var saved =
                  claimJournal.claim(row, "SUSPEND", human.binding(), clock.instant(), null);
              audit(id, "SUSPENDING", "DATABASE", "CLAIMED");
              return new ClaimedWork(saved, human);
            });
    if (checked == null) return true;
    try {
      return executeScoped(checked);
    } catch (RuntimeException failure) {
      recordScopedFailure(checked, failure);
      throw failure;
    } finally {
      checked.close();
    }
  }

  private boolean confirmNative(ClaimedWork checked, boolean restoreEnabled) {
    checked.requireCurrent();
    if (checked.terminalOnly
        || scopedTransport == null
        || !java.util.Set.of("SUSPEND", "RESTORE").contains(checked.action()))
      throw InactivityClaimJournal.invalid();
    if (checked.action().equals("RESTORE")) {
      var prior =
          jdbc.queryForMap(
              "select suspension_body,suspension_receipt from account_inactivity where identity_id=? and work_id=?",
              checked.identityId(),
              checked.workId());
      scopedTransport.verifyPriorSuspension(
          checked, (String) prior.get("suspension_body"), (String) prior.get("suspension_receipt"));
    }
    var current = claimJournal.saved(checked.identityId(), false);
    if ("CLAIMED".equals(current.phase())) {
      var projection = checked.nativeRead();
      var frozen = scopedTransport.freeze(checked, projection, restoreEnabled);
      scopedTransport.verifyFrozen(checked, frozen, restoreEnabled);
      work.executeWithoutResult(
          ignored -> {
            checked.requireOwned(true);
            if (jdbc.update(
                    "update account_inactivity set native_phase='FROZEN',native_body=? where identity_id=? and work_id=? and native_phase='CLAIMED' and native_body is null",
                    frozen.body(),
                    checked.identityId(),
                    checked.workId())
                != 1) throw InactivityClaimJournal.invalid();
          });
      current = claimJournal.saved(checked.identityId(), false);
    }
    var frozen =
        new de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.Frozen(
            current.body());
    scopedTransport.verifyFrozen(checked, frozen, restoreEnabled);
    if ("ACKNOWLEDGED".equals(current.phase())) {
      scopedTransport.verifyApplied(checked, frozen, current.receipt());
      return true;
    }
    if ("NO_EFFECT".equals(current.phase())) {
      var refused = scopedTransport.verifyNoEffect(checked, frozen, current.receipt());
      throw definiteRefusal(refused.status());
    }
    if (!"FROZEN".equals(current.phase()) || current.receipt() != null)
      throw InactivityClaimJournal.invalid();
    var result = scopedTransport.apply(checked, frozen);
    if (result
        instanceof
        de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.NoEffect
                refused) {
      var verified = scopedTransport.verifyNoEffect(checked, frozen, refused.receipt());
      work.executeWithoutResult(
          ignored -> {
            checked.requireOwned(true);
            if (jdbc.update(
                    "update account_inactivity set native_phase='NO_EFFECT',native_receipt=?,status=?,attempts=attempts+1,last_error=? where identity_id=? and work_id=? and native_phase='FROZEN' and native_body=? and native_receipt is null",
                    verified.receipt(),
                    checked.action().equals("SUSPEND") ? "ACTIVE" : "SUSPENDED",
                    "KEYCLOAK:NO_EFFECT_" + verified.status(),
                    checked.identityId(),
                    checked.workId(),
                    frozen.body())
                != 1) throw InactivityClaimJournal.invalid();
            audit(
                checked.identityId(),
                checked.original.path("status").textValue(),
                "KEYCLOAK",
                "NO_EFFECT_" + verified.status());
          });
      throw definiteRefusal(verified.status());
    }
    if (!(result
        instanceof
        de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.Applied applied))
      throw InactivityClaimJournal.invalid();
    String receipt = scopedTransport.verifyApplied(checked, frozen, applied.receipt());
    work.executeWithoutResult(
        ignored -> {
          checked.requireOwned(true);
          if (jdbc.update(
                  "update account_inactivity set native_phase='ACKNOWLEDGED',native_receipt=? where identity_id=? and work_id=? and native_phase='FROZEN' and native_body=? and native_receipt is null",
                  receipt,
                  checked.identityId(),
                  checked.workId(),
                  frozen.body())
              != 1) throw InactivityClaimJournal.invalid();
          if (checked.action().equals("SUSPEND"))
            jdbc.update(
                "update account_inactivity set suspension_operation_id=?,suspension_body=?,suspension_receipt=? where identity_id=? and work_id=?",
                checked.workId(),
                frozen.body(),
                receipt,
                checked.identityId(),
                checked.workId());
        });
    return true;
  }

  private RuntimeException definiteRefusal(int status) {
    if (status == 403)
      return new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
          "Inactivity operation is not authorized");
    if (status == 409)
      return new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
          "Inactivity operation conflicts with current state");
    return InactivityClaimJournal.invalid();
  }

  private boolean executeScoped(ClaimedWork checked) {
    checked.requireCurrent();
    boolean complete =
        switch (checked.action()) {
          case "SUSPEND" -> effects.suspend(checked);
          case "RESTORE" -> effects.reactivate(checked);
          case "DELETE" -> effects.delete(checked);
          default -> throw InactivityClaimJournal.invalid();
        };
    return Boolean.TRUE.equals(
        work.execute(
            ignored -> {
              checked.requireOwned(true);
              Status next =
                  switch (checked.action()) {
                    case "SUSPEND" -> Status.SUSPENDED;
                    case "RESTORE" -> Status.ACTIVE;
                    case "DELETE" -> Status.DELETED;
                    default -> throw InactivityClaimJournal.invalid();
                  };
              if (jdbc.update(
                      "update account_inactivity set attempts=attempts+1,last_error=?,status=? where identity_id=? and work_id=? and status=?",
                      complete ? null : "OTHER:UNCONFIRMED",
                      complete ? next.name() : checked.original.path("status").textValue(),
                      checked.identityId(),
                      checked.workId(),
                      checked.original.path("status").textValue())
                  != 1) throw InactivityClaimJournal.invalid();
              if (complete && next == Status.SUSPENDED)
                jdbc.update(
                    "update account_inactivity set suspension_operation_id=? where identity_id=? and work_id=?",
                    checked.workId(),
                    checked.identityId(),
                    checked.workId());
              if (complete && next == Status.ACTIVE) {
                var now = clock.instant();
                jdbc.update(
                    "update account_inactivity set last_activity=?,due_at=? where identity_id=? and work_id=?",
                    utc(now),
                    utc(due(now, checked.original.path("assignedMonths").intValue())),
                    checked.identityId(),
                    checked.workId());
              }
              audit(
                  checked.identityId(),
                  checked.original.path("status").textValue(),
                  "ALL",
                  complete ? "CONFIRMED" : "UNCONFIRMED");
              return complete;
            }));
  }

  private void recordScopedFailure(ClaimedWork checked, RuntimeException failure) {
    try {
      work.executeWithoutResult(
          ignored -> {
            checked.requireOwned(true);
            String target =
                failure instanceof AccountInactivityEffectException e ? e.target().name() : "OTHER";
            String code =
                failure instanceof AccountInactivityEffectException e ? e.code().name() : "FAILED";
            jdbc.update(
                "update account_inactivity set attempts=attempts+1,last_error=? where identity_id=? and work_id=?",
                target + ":" + code,
                checked.identityId(),
                checked.workId());
            audit(checked.identityId(), checked.original.path("status").textValue(), target, code);
          });
    } catch (RuntimeException changed) {
      /* A different current owner is never overwritten. */
    }
  }

  /** Only this service constructs a live frame from an actual owning persisted claim. */
  public final class ClaimedWork implements AutoCloseable {
    private final InactivityClaimJournal.Saved saved;
    private final com.fasterxml.jackson.databind.JsonNode original;
    private final ManualHuman human;
    private final boolean terminalOnly;
    private de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.Projection
        projection;
    private final Thread owner = Thread.currentThread();
    private final Instant deadline = clock.instant().plusSeconds(120);
    private boolean active = true;

    private ClaimedWork(InactivityClaimJournal.Saved saved, ManualHuman human) {
      this(saved, human, false);
    }

    private ClaimedWork(
        InactivityClaimJournal.Saved saved, ManualHuman human, boolean terminalOnly) {
      this.terminalOnly = terminalOnly;
      this.saved = saved;
      this.human = human;
      this.original = claimJournal.decode(saved.claimed());
      if (saved.work() == null
          || !java.util.UUID.fromString(saved.work()).toString().equals(saved.work())
          || !saved.work().equals(original.path("workId").textValue())
          || !saved.id().equals(original.path("identityId").textValue())
          || !java.util.UUID.fromString(saved.id()).toString().equals(saved.id()))
        throw InactivityClaimJournal.invalid();
      boolean manual = "HUMAN_ADMIN".equals(original.path("initiator").path("kind").textValue());
      if (!terminalOnly && manual != (human != null)) throw InactivityClaimJournal.invalid();
    }

    public String identityId() {
      return saved.id();
    }

    public String workId() {
      return saved.work();
    }

    public Long tenantId() {
      return original.path("tenantId").isNull()
          ? null
          : Long.valueOf(original.path("tenantId").textValue());
    }

    public String action() {
      return original.path("action").textValue();
    }

    public String originalRowJson() {
      requireCurrent();
      return saved.claimed();
    }

    public String originalSourceJson() {
      requireCurrent();
      return saved.source();
    }

    public String suspensionOperationId() {
      return original.path("suspensionOperationId").isNull()
          ? null
          : original.path("suspensionOperationId").textValue();
    }

    public String rawHumanBearer() {
      requireCurrent();
      if (human == null) return null;
      return human.bearer;
    }

    public boolean humanInitiated() {
      return human != null;
    }

    public boolean terminalOnly() {
      return terminalOnly;
    }

    public String humanSubject() {
      return human == null ? null : human.subject;
    }

    public String humanIssuer() {
      return human == null ? null : human.issuer;
    }

    public String humanClient() {
      return human == null ? null : human.client;
    }

    public String publicIssuer() {
      return scopedIssuer;
    }

    public de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.Projection
        nativeRead() {
      requireCurrent();
      if (terminalOnly || scopedTransport == null) throw InactivityClaimJournal.invalid();
      if (projection == null) projection = scopedTransport.read(this);
      if (projection == null
          || !identityId().equals(projection.id())
          || !java.util.Objects.equals(tenantId(), projection.tenantId()))
        throw InactivityClaimJournal.invalid();
      requireCurrent();
      return projection;
    }

    public boolean suspendNative() {
      if (!"SUSPEND".equals(action())) throw InactivityClaimJournal.invalid();
      return confirmNative(this, false);
    }

    public boolean restoreNative(boolean originalEnabled) {
      if (!"RESTORE".equals(action())) throw InactivityClaimJournal.invalid();
      return confirmNative(this, originalEnabled);
    }

    public void requireCurrent() {
      outsideSql();
      requireOwned(false);
    }

    public void requireOwnedSql() {
      if (!org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive()) throw InactivityClaimJournal.invalid();
      requireOwned(true);
    }

    private void requireOwned(boolean lock) {
      if (!active || owner != Thread.currentThread() || !clock.instant().isBefore(deadline))
        throw InactivityClaimJournal.invalid();
      if (human != null) {
        ManualHuman current = currentPlatformHuman();
        if (!human.subject.equals(current.subject)
            || !human.issuer.equals(current.issuer)
            || !human.client.equals(current.client)
            || !terminalOnly
                && !claimJournal
                    .encode(current.binding())
                    .equals(claimJournal.encode(original.path("initiator"))))
          throw InactivityClaimJournal.invalid();
      }
      var row = lock ? locked(identityId()).orElseThrow() : snapshot(identityId()).orElseThrow();
      var current = claimJournal.saved(identityId(), lock);
      if (!saved.work().equals(current.work())
          || !saved.claimed().equals(current.claimed())
          || !saved.source().equals(current.source())
          || !java.util.Objects.equals(tenantId(), row.tenantId())
          || original.path("assignedMonths").intValue() != row.assignedMonths()
          || original.path("revision").longValue() != row.revision()
          || original.path("lastActivityMicros").longValue()
              != InactivityClaimJournal.micros(row.lastActivity())
          || original.path("dueAtMicros").longValue() != InactivityClaimJournal.micros(row.dueAt())
          || !(terminalOnly
                  ? action().equals("RESTORE") ? "SUSPENDED" : "ACTIVE"
                  : original.path("status").textValue())
              .equals(row.status().name())
          || terminalOnly
              && (!"NO_EFFECT".equals(current.phase())
                  || current.receipt() == null
                  || current.body() == null)
          || !saved.source().equals(claimJournal.source(identityId(), tenantId(), lock)))
        throw InactivityClaimJournal.invalid();
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "ClaimedWork[ownership=redacted]";
    }
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
