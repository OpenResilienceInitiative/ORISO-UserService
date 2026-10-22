package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.VerifiedRestriction;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable reducing intent precedes native RPC; reservation reuse requires the saved exact ACK. */
public final class JdbcProtectedInvitationCancellation implements ProtectedInvitationCancellation {
  private final JdbcTemplate jdbc;
  private final JdbcProtectedInvitationJournal journal;
  private final ProtectedInvitationRestrictionCodec restrictions;
  private final AccountLifecycleCodec json;
  private final KeycloakProtectedRestrictions receiver;
  private final Function<Long, FrozenApproval> missingProposal;
  private final Clock clock;
  private final TransactionTemplate local;
  private final ProtectedCleanupStatusCodec cleanup;
  private final ProtectedAccountCodec accounts;
  private final JdbcProtectedInvitationAcceptance acceptance;
  private final JdbcProtectedAccountCreation creation;

  public JdbcProtectedInvitationCancellation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationRestrictionCodec restrictions,
      AccountLifecycleCodec json,
      KeycloakProtectedRestrictions receiver,
      Function<Long, FrozenApproval> missingProposal,
      Clock clock) {
    this(
        jdbc,
        manager,
        journal,
        restrictions,
        json,
        receiver,
        missingProposal,
        clock,
        null,
        null,
        null,
        null);
  }

  public JdbcProtectedInvitationCancellation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationRestrictionCodec restrictions,
      AccountLifecycleCodec json,
      KeycloakProtectedRestrictions receiver,
      Function<Long, FrozenApproval> missingProposal,
      Clock clock,
      ProtectedCleanupStatusCodec cleanup,
      ProtectedAccountCodec accounts,
      JdbcProtectedInvitationAcceptance acceptance,
      JdbcProtectedAccountCreation creation) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    this.jdbc = jdbc;
    this.journal = Objects.requireNonNull(journal);
    this.restrictions = Objects.requireNonNull(restrictions);
    this.json = Objects.requireNonNull(json);
    this.receiver = Objects.requireNonNull(receiver);
    this.missingProposal = Objects.requireNonNull(missingProposal);
    this.clock = Objects.requireNonNull(clock);
    local = new TransactionTemplate(manager);
    journal.requireOwner(jdbc, manager);
    boolean configured = cleanup != null;
    if (configured != (accounts != null)
        || configured != (acceptance != null)
        || configured != (creation != null)) throw unavailable();
    this.cleanup = cleanup;
    this.accounts = accounts;
    this.acceptance = acceptance;
    this.creation = creation;
  }

  /**
   * Independent current native terminal attestation; no caller-provided cleanup response suffices.
   */
  public void refreshAfterCleanup(
      long inviteId,
      ProtectedAccountCodec.VerifiedRecovery recovery,
      AccountLifecycleDtos.LocalOutcome localOutcome) {
    requireOutsideRecoverySql();
    requireCleanup();
    if (inviteId < 1 || recovery == null || localOutcome == null) throw unavailable();
    long expected = recovery.generation() - 1;
    if (expected < 0 || expected == Long.MAX_VALUE) throw unavailable();
    String recovered = json.utf8(accounts.recoveryResponse(recovery));
    String outcome = json.canonical(localOutcome);
    Row before =
        local.execute(
            tx -> {
              Row current = acknowledged(inviteId);
              var checked = authenticateRecovery(inviteId, expected, recovered);
              if (!checked.executionId().equals(recovery.executionId())) throw unavailable();
              StatusRow prior = statusRow(inviteId);
              if (prior != null && prior.finalStatus() != null) {
                checkedFinal(inviteId, current, prior);
                if (!same(prior.recovery(), recovered) || !same(prior.localOutcome(), outcome))
                  throw unavailable();
              }
              return current;
            });
    if (before == null) throw unavailable();
    var original = checkedAck(before);
    var remote = receiver.cleanupStatus(before.frozen(), original, recovery, localOutcome);
    if (remote == null) throw unavailable();
    String independent =
        cleanup
            .verify(
                before.frozen(),
                original,
                recovery,
                localOutcome,
                remote.responseJson().getBytes(StandardCharsets.UTF_8))
            .responseJson();
    local.executeWithoutResult(
        tx -> {
          Row current = acknowledged(inviteId);
          if (!before.frozen().equals(current.frozen())
              || !same(before.response(), current.response())) throw unavailable();
          var again = authenticateRecovery(inviteId, expected, recovered);
          cleanup.verify(
              current.frozen(),
              checkedAck(current),
              again,
              localOutcome,
              independent.getBytes(StandardCharsets.UTF_8));
          StatusRow prior = statusRow(inviteId);
          if (prior != null && prior.finalStatus() != null) {
            checkedFinal(inviteId, current, prior);
            if (!same(prior.recovery(), recovered) || !same(prior.localOutcome(), outcome))
              throw unavailable();
          }
          Timestamp now = Timestamp.from(clock.instant());
          if (prior == null)
            jdbc.update(
                "INSERT INTO protected_invitation_cleanup_status(invite_id,original_ack_json,recovery_json,local_outcome_json,final_status_json,expected_generation,recovery_id,last_attempt_at) VALUES(?,?,?,?,?,?,?,?)",
                inviteId,
                current.response(),
                recovered,
                outcome,
                independent,
                expected,
                again.executionId().toString(),
                now);
          else if (jdbc.update(
                  "UPDATE protected_invitation_cleanup_status SET original_ack_json=?,recovery_json=?,local_outcome_json=?,final_status_json=?,expected_generation=?,recovery_id=?,last_attempt_at=? WHERE invite_id=?",
                  current.response(),
                  recovered,
                  outcome,
                  independent,
                  expected,
                  again.executionId().toString(),
                  now,
                  inviteId)
              != 1) throw unavailable();
        });
  }

  /**
   * Reducing coordinator only: current accepted activation or already restricted original intent.
   */
  public void cancelPendingActivation(
      JdbcProtectedInvitationAcceptance.HistoricalAcceptance historical,
      JdbcProtectedAccountActivation activation) {
    requireOutsideRecoverySql();
    requireCleanup();
    if (historical == null || historical.inviteId() < 1 || activation == null) throw unavailable();
    long inviteId = historical.inviteId();
    cancel(
        inviteId,
        Reason.ISSUANCE_ABORTED,
        () -> {
          if ("ACCEPTED".equals(pendingActivationState(historical)))
            activation.restrictExpiredWithinOwner(historical);
        },
        () -> pendingActivationState(historical));
  }

  private String pendingActivationState(
      JdbcProtectedInvitationAcceptance.HistoricalAcceptance historical) {
    var current = acceptance.historicalWithinOwner(historical.inviteId()).claim();
    var expected = historical.claim();
    if (!current.frozen().equals(expected.frozen())
        || !same(current.approval().responseJson(), expected.approval().responseJson())
        || !current.claimId().equals(expected.claimId())
        || !current.attemptId().equals(expected.attemptId())
        || !current.executionId().equals(expected.executionId())
        || !same(current.registrationDigest(), expected.registrationDigest())
        || !Objects.equals(current.tokenHash(), expected.tokenHash())) throw unavailable();
    var phases =
        jdbc.queryForList(
            "SELECT state FROM protected_account_activation WHERE invite_id=? FOR UPDATE",
            String.class,
            historical.inviteId());
    if (phases.size() > 1 || phases.contains("COMMITTED")) throw unavailable();
    String state =
        jdbc.queryForObject(
            "SELECT status FROM account_invite WHERE id=?", String.class, historical.inviteId());
    if (!Set.of("ACCEPTED", "EXPIRED", "REVOKED", "SUPERSEDED").contains(state))
      throw unavailable();
    return state;
  }

  @Override
  public void cancel(long inviteId, Reason reason, Runnable invalidateLocalInvite) {
    cancel(inviteId, reason, invalidateLocalInvite, () -> {});
  }

  private void cancel(
      long inviteId, Reason reason, Runnable invalidateLocalInvite, Runnable currentOwnerGuard) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException(
          "Protected cancellation requires a completed local transaction");
    if (inviteId <= 0 || reason == null || invalidateLocalInvite == null) throw unavailable();
    Row pending =
        local.execute(
            tx -> {
              FrozenApproval proposal = journal.lockedRestrictionProposal(inviteId);
              currentOwnerGuard.run();
              Row current = row(inviteId, proposal);
              if (current != null) {
                journal.requireRestrictedInvite(
                    inviteId, current.frozen().approval(), "ACKNOWLEDGED".equals(current.state()));
                if ("ACKNOWLEDGED".equals(current.state())) checkedAck(current);
                touch(inviteId);
                return current;
              }
              invalidateLocalInvite.run();
              var original =
                  journal.requestRestriction(inviteId, () -> missingProposal.apply(inviteId));
              FrozenCancellation frozen =
                  restrictions.freeze(
                      original.approval(), original.knownScopeDigest(), UUID.randomUUID(), reason);
              Timestamp now = Timestamp.from(clock.instant());
              jdbc.update(
                  "INSERT INTO protected_invitation_cancellation"
                      + " (invite_id,approval_id,cancellation_id,reason,known_scope_digest,body_json,state,response_json,created_at,updated_at)"
                      + " VALUES (?,?,?,?,?,?,'PENDING',NULL,?,?)",
                  inviteId,
                  frozen.approval().approvalId().toString(),
                  frozen.cancellationId().toString(),
                  frozen.reason().name(),
                  frozen.knownScopeDigest(),
                  frozen.bodyJson(),
                  now,
                  now);
              journal.requireRestrictedInvite(inviteId, frozen.approval(), false);
              return new Row(frozen, "PENDING", null);
            });
    if (pending == null) throw unavailable();
    if ("ACKNOWLEDGED".equals(pending.state())) return;
    VerifiedRestriction ack;
    try {
      ack = receiver.cancel(pending.frozen());
    } catch (KeycloakProtectedInvitations.RemoteFailure failure) {
      if (failure.status() != 409) throw failure;
      ack = receiver.reconcile(pending.frozen());
    }
    if (ack == null) throw unavailable();
    String response;
    try {
      response =
          json.canonical(
              json.read(ack.responseJson(), com.fasterxml.jackson.databind.JsonNode.class));
      restrictions.verify(pending.frozen(), response.getBytes(StandardCharsets.UTF_8), true);
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
    local.executeWithoutResult(
        tx -> {
          FrozenApproval proposal = journal.lockedRestrictionProposal(inviteId);
          Row current = row(inviteId, proposal);
          if (current == null || !pending.frozen().equals(current.frozen())) throw unavailable();
          journal.requireRestrictedInvite(
              inviteId, current.frozen().approval(), "ACKNOWLEDGED".equals(current.state()));
          if ("ACKNOWLEDGED".equals(current.state())) {
            checkedAck(current); // The first valid reducing terminal remains immutable.
            return;
          }
          if (jdbc.update(
                  "UPDATE protected_invitation_cancellation SET state='ACKNOWLEDGED',response_json=?,updated_at=?"
                      + " WHERE invite_id=? AND state='PENDING' AND cancellation_id=?",
                  response,
                  Timestamp.from(clock.instant()),
                  inviteId,
                  current.frozen().cancellationId().toString())
              != 1) throw unavailable();
        });
  }

  @Override
  public <T> T withAcknowledged(long inviteId, Supplier<T> releaseOrTransfer) {
    if (releaseOrTransfer == null) throw unavailable();
    return local.execute(
        tx -> {
          FrozenApproval proposal = journal.lockedRestrictionProposal(inviteId);
          Row current = row(inviteId, proposal);
          if (current == null || !"ACKNOWLEDGED".equals(current.state())) throw unavailable();
          journal.requireRestrictedInvite(inviteId, current.frozen().approval(), true);
          if (checkedAck(current).cleanupRequired()) {
            requireCleanup();
            checkedFinal(inviteId, current, statusRow(inviteId));
          }
          T result = releaseOrTransfer.get();
          if (cleanup != null && jdbc.queryForObject(
              "SELECT COUNT(*) FROM account_invite WHERE id=? AND status IN ('REVOKED','EXPIRED','SUPERSEDED') AND active_recipient_key IS NULL AND tenant_id_reservation_token IS NULL",
              Integer.class, inviteId) == 1) {
            Timestamp now = Timestamp.from(clock.instant());
            if (statusRow(inviteId) == null)
              jdbc.update("INSERT INTO protected_invitation_cleanup_status(invite_id,last_attempt_at,completed_at) VALUES(?,?,?)", inviteId, now, now);
            else
              jdbc.update("UPDATE protected_invitation_cleanup_status SET completed_at=COALESCE(completed_at,?) WHERE invite_id=?", now, inviteId);
          }
          return result;
        });
  }

  /** Authenticated saved fence data only; cleanup-required acknowledgements cannot release. */
  public VerifiedRestriction verifiedRestriction(long inviteId) {
    if (inviteId < 1
        || TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
    return local.execute(
        tx -> {
          FrozenApproval proposal = journal.lockedRestrictionProposal(inviteId);
          Row current = row(inviteId, proposal);
          if (current == null || !"ACKNOWLEDGED".equals(current.state())) throw unavailable();
          journal.requireRestrictedInvite(inviteId, current.frozen().approval(), true);
          return checkedAck(current);
        });
  }

  private Row row(long inviteId, FrozenApproval proposal) {
    var found =
        jdbc.query(
            "SELECT * FROM protected_invitation_cancellation WHERE invite_id=? FOR UPDATE",
            (rs, index) -> {
              if (proposal == null
                  || !proposal.approvalId().toString().equals(rs.getString("approval_id")))
                throw unavailable();
              var frozen =
                  restrictions.freeze(
                      proposal,
                      rs.getString("known_scope_digest"),
                      UUID.fromString(rs.getString("cancellation_id")),
                      Reason.valueOf(rs.getString("reason")));
              if (!frozen.bodyJson().equals(rs.getString("body_json"))) throw unavailable();
              String state = rs.getString("state");
              if (!Set.of("PENDING", "ACKNOWLEDGED").contains(state)) throw unavailable();
              return new Row(frozen, state, rs.getString("response_json"));
            },
            inviteId);
    if (found.size() > 1) throw unavailable();
    return found.isEmpty() ? null : found.get(0);
  }

  @Override
  public List<Long> pendingInviteIds(int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid protected recovery page");
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Protected recovery discovery requires completed local SQL");
    return jdbc.queryForList(
        "SELECT i.id FROM account_invite i LEFT JOIN protected_invitation_cancellation c ON c.invite_id=i.id"
            + " WHERE i.target_role IN ('TENANT_ADMIN','PLATFORM_ADMIN') AND i.purpose='INVITE'"
            + " AND i.status IN ('EXPIRED','REVOKED','SUPERSEDED')"
            + " AND (c.invite_id IS NULL OR c.state='PENDING'"
            + " OR (c.state='ACKNOWLEDGED' AND i.status='SUPERSEDED'"
            + " AND i.superseded_by_invite_id IS NULL AND i.tenant_id_reservation_token IS NOT NULL AND i.expires_at<=?))"
            + " ORDER BY COALESCE(c.updated_at,i.update_date,i.expires_at),i.id LIMIT ?",
        Long.class,
        Timestamp.from(clock.instant()),
        limit);
  }

  /** Includes committed-reply recovery and restricted cleanup holds; separate bounded worker. */
  public List<Long> recoveryInviteIds(int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid protected recovery page");
    requireOutsideRecoverySql();
    return jdbc.queryForList(
        "SELECT i.id FROM account_invite i"
            + " LEFT JOIN protected_account_activation a ON a.invite_id=i.id"
            + " LEFT JOIN protected_invitation_cancellation c ON c.invite_id=i.id"
            + " LEFT JOIN protected_invitation_cleanup_status s ON s.invite_id=i.id"
            + " WHERE i.target_role IN ('TENANT_ADMIN','PLATFORM_ADMIN') AND i.purpose='INVITE'"
            + " AND (a.state IS NULL OR a.state<>'COMMITTED') AND s.completed_at IS NULL"
            + " AND ((a.state='READY' AND i.status IN ('ACCEPTED','EXPIRED','REVOKED','SUPERSEDED'))"
            + " OR (a.state='WAITING_OTP' AND i.status='ACCEPTED' AND i.provisioning_status='IN_PROGRESS' AND i.expires_at<=?)"
            + " OR (i.status IN ('EXPIRED','REVOKED','SUPERSEDED') AND (c.invite_id IS NULL OR c.state IN ('PENDING','ACKNOWLEDGED'))))"
            + " ORDER BY COALESCE(s.last_attempt_at,c.updated_at,a.updated_at,i.update_date,i.expires_at),i.id LIMIT ?",
        Long.class,
        Timestamp.from(clock.instant()),
        limit);
  }

  /** Fairness metadata only; the callback still needs independently checked original ownership. */
  public void touchRecovery(long inviteId) {
    requireOutsideRecoverySql();
    if (inviteId < 1) throw new IllegalArgumentException("Invalid protected recovery target");
    local.executeWithoutResult(
        tx -> {
          var found =
              jdbc.queryForList(
                  "SELECT id FROM account_invite WHERE id=? AND target_role IN ('TENANT_ADMIN','PLATFORM_ADMIN') AND purpose='INVITE' FOR UPDATE",
                  Long.class,
                  inviteId);
          if (found.size() != 1) throw unavailable();
          var cursor =
              jdbc.queryForList(
                  "SELECT invite_id FROM protected_invitation_cleanup_status WHERE invite_id=? FOR UPDATE",
                  Long.class,
                  inviteId);
          if (cursor.isEmpty())
            jdbc.update(
                "INSERT INTO protected_invitation_cleanup_status(invite_id,last_attempt_at) VALUES(?,?)",
                inviteId,
                Timestamp.from(clock.instant()));
          else {
            if (cursor.size() != 1) throw unavailable();
            jdbc.update(
                "UPDATE protected_invitation_cleanup_status SET last_attempt_at=? WHERE invite_id=?",
                Timestamp.from(clock.instant()),
                inviteId);
          }
        });
  }

  private static void requireOutsideRecoverySql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty())
      throw new IllegalStateException("Protected recovery requires completed local SQL");
  }

  private void requireCleanup() {
    if (cleanup == null || accounts == null || acceptance == null || creation == null)
      throw unavailable();
  }

  private Row acknowledged(long inviteId) {
    FrozenApproval proposal = journal.lockedRestrictionProposal(inviteId);
    Row current = row(inviteId, proposal);
    if (current == null || !"ACKNOWLEDGED".equals(current.state())) throw unavailable();
    journal.requireRestrictedInvite(inviteId, current.frozen().approval(), true);
    checkedAck(current);
    return current;
  }

  private ProtectedAccountCodec.VerifiedRecovery authenticateRecovery(
      long inviteId, long expected, String response) {
    requireCleanup();
    var history = acceptance.historicalWithinOwner(inviteId);
    var saved = creation.recoveryWithinOwner(history, expected);
    return accounts.verifyRecovery(saved, response.getBytes(StandardCharsets.UTF_8));
  }

  private void checkedFinal(long inviteId, Row current, StatusRow saved) {
    if (saved == null
        || saved.originalAck() == null
        || saved.recovery() == null
        || saved.localOutcome() == null
        || saved.finalStatus() == null
        || saved.expected() == null
        || saved.expected() < 0
        || saved.expected() == Long.MAX_VALUE
        || saved.recoveryId() == null
        || !same(current.response(), saved.originalAck())) throw unavailable();
    var recovered = authenticateRecovery(inviteId, saved.expected(), saved.recovery());
    if (!recovered.executionId().toString().equals(saved.recoveryId())
        || recovered.generation() != saved.expected() + 1) throw unavailable();
    var local = json.read(saved.localOutcome(), AccountLifecycleDtos.LocalOutcome.class);
    if (!same(json.canonical(local), saved.localOutcome())) throw unavailable();
    cleanup.verify(
        current.frozen(),
        checkedAck(current),
        recovered,
        local,
        saved.finalStatus().getBytes(StandardCharsets.UTF_8));
  }

  private StatusRow statusRow(long inviteId) {
    var rows =
        jdbc.query(
            "SELECT original_ack_json,recovery_json,local_outcome_json,final_status_json,expected_generation,recovery_id FROM protected_invitation_cleanup_status WHERE invite_id=? FOR UPDATE",
            (rs, index) ->
                new StatusRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, Long.class),
                    rs.getString(6)),
            inviteId);
    if (rows.size() > 1) throw unavailable();
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private record StatusRow(
      String originalAck,
      String recovery,
      String localOutcome,
      String finalStatus,
      Long expected,
      String recoveryId) {
    @Override
    public String toString() {
      return "ProtectedFinalStatus[redacted]";
    }
  }

  private static boolean same(String left, String right) {
    return left != null
        && right != null
        && java.security.MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private VerifiedRestriction checkedAck(Row row) {
    try {
      if (row.response() == null) throw unavailable();
      return restrictions.verify(
          row.frozen(), row.response().getBytes(StandardCharsets.UTF_8), true);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private void touch(long inviteId) {
    jdbc.update(
        "UPDATE protected_invitation_cancellation SET updated_at=? WHERE invite_id=? AND state IN ('PENDING','ACKNOWLEDGED')",
        Timestamp.from(clock.instant()),
        inviteId);
  }

  private static AccessDeniedException unavailable() {
    return new AccessDeniedException("Protected invitation restriction is pending or unavailable");
  }

  private record Row(FrozenCancellation frozen, String state, String response) {
    @Override
    public String toString() {
      return "ProtectedRestriction[redacted]";
    }
  }
}
