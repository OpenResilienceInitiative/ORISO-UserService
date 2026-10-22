package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Protected issuance has its own durable proposal; no bearer is stored. */
public final class JdbcProtectedInvitationJournal {
  private final JdbcTemplate jdbc;
  private final ProtectedInvitationCodec codec;
  private final AccountLifecycleCodec json;
  private final Clock clock;
  private final TransactionTemplate local;
  private final TransactionTemplate durable;
  private final PlatformTransactionManager manager;

  public JdbcProtectedInvitationJournal(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ProtectedInvitationCodec codec,
      AccountLifecycleCodec json,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    this.manager = manager;
    this.jdbc = jdbc;
    this.codec = codec;
    this.json = json;
    this.clock = clock;
    local = new TransactionTemplate(manager);
    durable = new TransactionTemplate(manager);
    durable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  void requireOwner(JdbcTemplate selected, PlatformTransactionManager transactions) {
    if (selected.getDataSource() != jdbc.getDataSource() || transactions != manager)
      throw new IllegalArgumentException("Protected invitation journal transaction owner mismatch");
  }

  public FrozenApproval propose(long inviteId, FrozenApproval proposal) {
    // The caller must first commit the actual resolved invitation. Never wait on its own row lock.
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    codec.approvalBody(proposal);
    return durable.execute(
        tx -> {
          Invite invite = lockInvite(inviteId);
          requireCurrent(inviteId, invite, proposal);
          var rows = rows(inviteId);
          if (!rows.isEmpty()) {
            Row current = rows.get(0);
            if (!current.proposal().equals(proposal)
                || !Set.of("APPROVAL_REQUESTED", "READY").contains(current.state()))
              throw unavailable();
            return current.proposal();
          }
          JsonNode scope = scope(proposal);
          Timestamp now = Timestamp.from(clock.instant());
          jdbc.update(
              "INSERT INTO protected_invitation_authorization"
                  + " (approval_id,invite_id,workflow_id,authorization_revision,request_json,issuer_realm,"
                  + "issuer_subject,request_digest,state,response_json,created_at,updated_at)"
                  + " VALUES (?,?,?,?,?,?,?,?, 'APPROVAL_REQUESTED',NULL,?,?)",
              proposal.approvalId().toString(),
              inviteId,
              scope.path("workflowId").asText(),
              scope.path("authorizationRevision").longValue(),
              proposal.requestJson(),
              proposal.issuerRealm(),
              proposal.issuerSubject(),
              proposal.requestDigest(),
              now,
              now);
          return proposal;
        });
  }

  public FrozenApproval pending(long inviteId) {
    var found =
        jdbc.query(
            "SELECT * FROM protected_invitation_authorization WHERE invite_id=?",
            (rs, index) -> frozen(rs),
            inviteId);
    if (found.size() != 1) throw unavailable();
    codec.approvalBody(found.get(0));
    return found.get(0);
  }

  public void recordReady(UUID approvalId, VerifiedApproval approval) {
    if (approvalId == null || approval == null || !approvalId.equals(approval.approvalId()))
      throw unavailable();
    var ids =
        jdbc.queryForList(
            "SELECT invite_id FROM protected_invitation_authorization WHERE approval_id=?",
            Long.class,
            approvalId.toString());
    if (ids.size() != 1) throw unavailable();
    local.executeWithoutResult(
        tx -> {
          long inviteId = ids.get(0);
          Invite invite = lockInvite(inviteId);
          Row row = row(inviteId);
          if (!approvalId.equals(row.proposal().approvalId())
              || !Set.of("APPROVAL_REQUESTED", "READY").contains(row.state())) throw unavailable();
          requireCurrent(inviteId, invite, row.proposal());
          var checked = codec.verifyAuthorized(row.proposal(), approval.responseJson());
          if (!checked.requestDigest().equals(approval.requestDigest())
              || !checked.receiptJson().equals(approval.receiptJson())) throw unavailable();
          if ("READY".equals(row.state())) {
            if (!row.response().equals(checked.responseJson())) throw unavailable();
            return;
          }
          if (jdbc.update(
                  "UPDATE protected_invitation_authorization SET state='READY',response_json=?,updated_at=?"
                      + " WHERE approval_id=? AND state='APPROVAL_REQUESTED'",
                  checked.responseJson(),
                  Timestamp.from(clock.instant()),
                  approvalId.toString())
              != 1) throw unavailable();
        });
  }

  public <T> T withReadyInvite(long inviteId, Supplier<T> release) {
    if (release == null) throw unavailable();
    return local.execute(
        tx -> {
          Invite invite = lockInvite(inviteId);
          Row row = row(inviteId);
          if (!"READY".equals(row.state()) || row.response() == null) throw unavailable();
          requireCurrent(inviteId, invite, row.proposal());
          codec.verifyAuthorized(row.proposal(), row.response());
          return release.get();
        });
  }

  public void cancel(long inviteId, Runnable invalidateLocalInvite) {
    if (invalidateLocalInvite == null) throw unavailable();
    local.executeWithoutResult(
        tx -> {
          lockInvite(inviteId);
          Row row = row(inviteId);
          if (!Set.of("APPROVAL_REQUESTED", "READY", "CANCEL_REQUESTED").contains(row.state()))
            throw unavailable();
          jdbc.update(
              "UPDATE protected_invitation_authorization SET state='CANCEL_REQUESTED',updated_at=?"
                  + " WHERE approval_id=?",
              Timestamp.from(clock.instant()),
              row.proposal().approvalId().toString());
          invalidateLocalInvite.run();
        });
  }

  <T> T withRecipientInvite(long inviteId, Supplier<T> action) {
    if (action == null) throw unavailable();
    return local.execute(
        tx -> {
          Invite invite = lockInvite(inviteId);
          Row row = row(inviteId);
          if (!"READY".equals(row.state()) || row.response() == null) throw unavailable();
          requireCurrent(inviteId, invite, row.proposal(), true, false, true);
          codec.verifyAuthorized(row.proposal(), row.response());
          return action.get();
        });
  }

  /** Original saved evidence for reducing/status-only operations; never a live recipient grant. */
  <T> T withHistoricalInvite(long inviteId, Supplier<T> action) {
    if (action == null) throw unavailable();
    return local.execute(
        tx -> {
          Invite invite = lockInvite(inviteId);
          Row row = row(inviteId);
          if (!Set.of("READY", "CANCEL_REQUESTED").contains(row.state())
              || row.response() == null) throw unavailable();
          requireCurrent(inviteId, invite, row.proposal(), false, true, true, true);
          codec.verifyAuthorizedHistory(row.proposal(), row.response());
          return action.get();
        });
  }

  RestrictionProposal requestRestriction(long inviteId, Supplier<FrozenApproval> missingProposal) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    Invite invite = lockInvite(inviteId);
    var found = rows(inviteId);
    if (found.size() > 1) throw unavailable();
    FrozenApproval proposal = found.isEmpty() ? missingProposal.get() : found.get(0).proposal();
    requireCurrent(inviteId, invite, proposal, false);
    String known = null;
    if (found.isEmpty()) {
      JsonNode scope = scope(proposal);
      Timestamp now = Timestamp.from(clock.instant());
      jdbc.update(
          "INSERT INTO protected_invitation_authorization"
              + " (approval_id,invite_id,workflow_id,authorization_revision,request_json,issuer_realm,"
              + "issuer_subject,request_digest,state,response_json,created_at,updated_at)"
              + " VALUES (?,?,?,?,?,?,?,?, 'CANCEL_REQUESTED',NULL,?,?)",
          proposal.approvalId().toString(),
          inviteId,
          scope.path("workflowId").asText(),
          scope.path("authorizationRevision").longValue(),
          proposal.requestJson(),
          proposal.issuerRealm(),
          proposal.issuerSubject(),
          proposal.requestDigest(),
          now,
          now);
    } else {
      Row row = found.get(0);
      if (!Set.of("APPROVAL_REQUESTED", "READY", "CANCEL_REQUESTED").contains(row.state()))
        throw unavailable();
      if (row.response() != null) {
        JsonNode response = json.read(row.response(), JsonNode.class);
        if (!response.path("scopeDigest").isTextual()) throw unavailable();
        known = response.path("scopeDigest").textValue();
      }
      jdbc.update(
          "UPDATE protected_invitation_authorization SET state='CANCEL_REQUESTED',updated_at=? WHERE approval_id=?",
          Timestamp.from(clock.instant()),
          proposal.approvalId().toString());
    }
    return new RestrictionProposal(proposal, known);
  }

  record RestrictionProposal(FrozenApproval approval, String knownScopeDigest) {
    @Override
    public String toString() {
      return "RestrictionProposal[redacted]";
    }
  }

  FrozenApproval lockedRestrictionProposal(long inviteId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    lockInvite(inviteId);
    var found = rows(inviteId);
    if (found.size() > 1) throw unavailable();
    return found.isEmpty() ? null : found.get(0).proposal();
  }

  void requireRestrictedInvite(long inviteId, FrozenApproval proposal, boolean acknowledged) {
    Invite invite = lockInvite(inviteId);
    Row saved = row(inviteId);
    if (!"CANCEL_REQUESTED".equals(saved.state())
        || !saved.proposal().equals(proposal)
        || !Set.of("REVOKED", "EXPIRED", "SUPERSEDED").contains(invite.status()))
      throw unavailable();
    requireCurrent(inviteId, invite, proposal, false, acknowledged);
  }

  private Invite lockInvite(long id) {
    var found =
        jdbc.query(
            "SELECT target_role,tenant_id,recipient_email,purpose,status,expires_at,"
                + "agency_id,tenant_id_reservation_token FROM account_invite WHERE id=? FOR UPDATE",
            (rs, index) ->
                new Invite(
                    rs.getString("target_role"),
                    rs.getObject("tenant_id", Long.class),
                    rs.getString("recipient_email"),
                    rs.getString("purpose"),
                    rs.getString("status"),
                    rs.getTimestamp("expires_at"),
                    rs.getObject("agency_id", Long.class),
                    rs.getString("tenant_id_reservation_token")),
            id);
    if (found.size() != 1) throw unavailable();
    return found.get(0);
  }

  private Row row(long inviteId) {
    var found = rows(inviteId);
    if (found.size() != 1) throw unavailable();
    return found.get(0);
  }

  private java.util.List<Row> rows(long inviteId) {
    return jdbc.query(
        "SELECT * FROM protected_invitation_authorization WHERE invite_id=? FOR UPDATE",
        (rs, index) -> new Row(frozen(rs), rs.getString("state"), rs.getString("response_json")),
        inviteId);
  }

  private FrozenApproval frozen(java.sql.ResultSet rs) throws java.sql.SQLException {
    FrozenApproval saved =
        new FrozenApproval(
            UUID.fromString(rs.getString("approval_id")),
            rs.getString("request_json"),
            rs.getString("issuer_realm"),
            rs.getString("issuer_subject"),
            rs.getString("request_digest"));
    JsonNode scope = scope(saved);
    if (!scope.path("workflowId").asText().equals(rs.getString("workflow_id"))
        || scope.path("authorizationRevision").longValue() != rs.getLong("authorization_revision"))
      throw unavailable();
    return saved;
  }

  private JsonNode scope(FrozenApproval proposal) {
    return json.read(codec.approvalBody(proposal), JsonNode.class).path("scope");
  }

  private void requireCurrent(long inviteId, Invite invite, FrozenApproval proposal) {
    requireCurrent(inviteId, invite, proposal, true);
  }

  private void requireCurrent(long inviteId, Invite invite, FrozenApproval proposal, boolean live) {
    requireCurrent(inviteId, invite, proposal, live, false);
  }

  private void requireCurrent(
      long inviteId, Invite invite, FrozenApproval proposal, boolean live, boolean released) {
    requireCurrent(inviteId, invite, proposal, live, released, false);
  }

  private void requireCurrent(
      long inviteId,
      Invite invite,
      FrozenApproval proposal,
      boolean live,
      boolean released,
      boolean recipient) {
    requireCurrent(inviteId, invite, proposal, live, released, recipient, false);
  }

  private void requireCurrent(
      long inviteId,
      Invite invite,
      FrozenApproval proposal,
      boolean live,
      boolean released,
      boolean recipient,
      boolean historical) {
    JsonNode scope = scope(proposal);
    Instant deadline = Instant.parse(scope.path("validUntil").asText());
    if (!Long.toString(inviteId).equals(scope.path("inviteId").asText())
        || !"INVITE".equals(invite.purpose())
        || !(historical
                ? Set.of("EMAIL_SENT", "ACCEPTED", "EXPIRED", "REVOKED", "SUPERSEDED")
                : recipient
                ? Set.of("EMAIL_SENT", "ACCEPTED")
                : live
                    ? Set.of("DRAFT", "EMAIL_SENT")
                    : Set.of("DRAFT", "EMAIL_SENT", "EXPIRED", "REVOKED", "SUPERSEDED"))
            .contains(invite.status())
        || !scope.path("protectedKind").asText().equals(invite.role())
        || invite.tenant() == null
        || scope.path("targetTenantIds").get(0).longValue() != invite.tenant()
        || !scope.path("recipient").asText().equals(invite.recipient())
        || invite.expires() == null
        || !deadline.equals(invite.expires().toInstant())
        || live && !deadline.isAfter(clock.instant())) throw unavailable();
    JsonNode agency = scope.path("agencyBinding");
    if (invite.agency() == null
        ? !agency.isNull()
        : agency.isNull()
            || agency.path("agencyId").longValue() != invite.agency()
            || agency.path("tenantId").longValue() != invite.tenant()) throw unavailable();
    if (invite.reservation() == null) {
      if (released
          && "RESERVED_NEW".equals(scope.path("tenantMode").asText())
          && scope.path("reservationBinding").isObject()) return;
      if (!"EXISTING".equals(scope.path("tenantMode").asText())
          || !scope.path("reservationBinding").isNull()) throw unavailable();
    } else {
      JsonNode reservation = scope.path("reservationBinding");
      if (invite.reservation().isBlank()
          || !"RESERVED_NEW".equals(scope.path("tenantMode").asText())
          || reservation.isNull()
          || !reservation
              .path("capabilityDigest")
              .asText()
              .equals(
                  json.digest(
                      "protected-reservation-capability/v1",
                      Map.of(
                          "workflowId",
                          scope.path("workflowId").asText(),
                          "tenantId",
                          invite.tenant(),
                          "capability",
                          invite.reservation())))) throw unavailable();
    }
  }

  private static AccessDeniedException unavailable() {
    return new AccessDeniedException(
        "Protected invitation authorization is pending or unavailable");
  }

  private record Invite(
      String role,
      Long tenant,
      String recipient,
      String purpose,
      String status,
      Timestamp expires,
      Long agency,
      String reservation) {
    @Override
    public String toString() {
      return "ProtectedInvite[redacted]";
    }
  }

  private record Row(FrozenApproval proposal, String state, String response) {
    @Override
    public String toString() {
      return "ProtectedAuthorization[redacted]";
    }
  }
}
