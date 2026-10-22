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

  public JdbcProtectedInvitationCancellation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationRestrictionCodec restrictions,
      AccountLifecycleCodec json,
      KeycloakProtectedRestrictions receiver,
      Function<Long, FrozenApproval> missingProposal,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    this.jdbc = jdbc;
    this.journal = Objects.requireNonNull(journal);
    this.restrictions = Objects.requireNonNull(restrictions);
    this.json = Objects.requireNonNull(json);
    this.receiver = Objects.requireNonNull(receiver);
    this.missingProposal = Objects.requireNonNull(missingProposal);
    this.clock = Objects.requireNonNull(clock);
    local = new TransactionTemplate(manager);
  }

  @Override
  public void cancel(long inviteId, Reason reason, Runnable invalidateLocalInvite) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException(
          "Protected cancellation requires a completed local transaction");
    if (inviteId <= 0 || reason == null || invalidateLocalInvite == null) throw unavailable();
    Row pending =
        local.execute(
            tx -> {
              FrozenApproval proposal = journal.lockedRestrictionProposal(inviteId);
              Row current = row(inviteId, proposal);
              if (current != null) {
                journal.requireRestrictedInvite(
                    inviteId, current.frozen().approval(), "ACKNOWLEDGED".equals(current.state()));
                if ("ACKNOWLEDGED".equals(current.state())) checkedAck(current);
                touch(inviteId);
                return current;
              }
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
              invalidateLocalInvite.run();
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
          if (checkedAck(current).cleanupRequired()) throw unavailable();
          return releaseOrTransfer.get();
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
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid protected recovery page");
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
        Long.class,Timestamp.from(clock.instant()),limit);
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
