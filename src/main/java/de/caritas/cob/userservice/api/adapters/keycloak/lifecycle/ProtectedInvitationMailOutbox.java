package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationMailSecrets.Metadata;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailOrigin;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailDelivery.Purpose;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable protected mail; native approval and the actual invitation precede usable delivery. */
public final class ProtectedInvitationMailOutbox {
  private final JdbcTemplate jdbc;
  private final JdbcProtectedInvitationJournal journal;
  private final AccountLifecycleCodec json;
  private final ProtectedInvitationMailSecrets secrets;
  private final String realmId;
  private final Clock clock;
  private final KeycloakProtectedInvitations approvals;
  private final InviteMailDispatchService sender;
  private final TransactionTemplate acknowledgement;
  private final ObjectMapper mailReader = new ObjectMapper();

  public ProtectedInvitationMailOutbox(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      JdbcProtectedInvitationJournal journal,
      AccountLifecycleCodec json,
      byte[] contextKey,
      String realmId,
      Clock clock,
      KeycloakProtectedInvitations approvals,
      InviteMailDispatchService sender) {
    ProtectedInvitationSqlOwner.require(jdbc, transactions);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.journal = Objects.requireNonNull(journal);
    this.json = Objects.requireNonNull(json);
    this.secrets = new ProtectedInvitationMailSecrets(contextKey, realmId);
    this.realmId = realmId;
    this.clock = Objects.requireNonNull(clock);
    this.approvals = Objects.requireNonNull(approvals);
    this.sender = Objects.requireNonNull(sender);
    acknowledgement = new TransactionTemplate(Objects.requireNonNull(transactions));
    acknowledgement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** The token supplier must flush the actual invite in this same owning SQL transaction. */
  public void enqueue(long inviteId, Supplier<Mail> prepareAndPersistToken) {
    if (prepareAndPersistToken == null) throw unavailable(false);
    journal.withReadyInvite(
        inviteId,
        () -> {
          requireOwningSql();
          var existing = rows(inviteId, true);
          if (!existing.isEmpty()) {
            Row row = existing.get(0);
            if (!Set.of("READY", "SENDING", "SENT", "UNKNOWN", "FAILED_NO_EFFECT")
                .contains(row.state())) throw unavailable(false);
            Checked checked = checked(inviteId, row);
            requireToken(inviteId, checked.metadata());
            return null;
          }
          Mail mail = Objects.requireNonNull(prepareAndPersistToken.get());
          // The callback may only install the token/status, never drift the approved target tuple.
          journal.withReadyInvite(inviteId, () -> null);
          var frozen = journal.pending(inviteId);
          JsonNode scope = json.read(frozen.requestJson(), JsonNode.class).path("scope");
          JsonNode response = savedResponse(inviteId);
          Invite invite = invite(inviteId);
          Metadata metadata =
              new Metadata(
                  1,
                  realmId,
                  frozen.approvalId().toString(),
                  scope.path("workflowId").asText(),
                  inviteId,
                  scope.path("authorizationRevision").longValue(),
                  response.path("scopeDigest").asText(),
                  frozen.requestDigest(),
                  invite.tokenHash(),
                  json.digest("protected-invitation-mail-payload/v1", mail));
          String sealed = secrets.seal(metadata, json.canonical(mail));
          requireToken(inviteId, metadata);
          Timestamp now = Timestamp.from(clock.instant());
          if (jdbc.update(
                  "INSERT INTO protected_invitation_mail_outbox"
                      + " (invite_id,approval_id,metadata_json,sealed_mail,state,sent_at,created_at,updated_at)"
                      + " VALUES (?,?,?,?,'READY',NULL,?,?)",
                  inviteId,
                  metadata.approvalId(),
                  json.canonical(metadata),
                  sealed,
                  now,
                  now)
              != 1) throw unavailable(false);
          return null;
        });
  }

  public InviteMailSendReceipt dispatch(long inviteId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable(false);
    Row observed = only(rows(inviteId, false));
    // This is a historical SMTP receipt, not a new invitation authorization.
    if ("SENT".equals(observed.state())) return historical(inviteId, observed);
    if (!"READY".equals(observed.state()))
      throw unavailable("FAILED_NO_EFFECT".equals(observed.state()));
    var frozen = journal.pending(inviteId);
    var verified = approvals.reconcile(frozen);
    journal.recordReady(frozen.approvalId(), verified);
    Claimed claim =
        journal.withReadyInvite(
            inviteId,
            () -> {
              requireOwningSql();
              Row row = only(rows(inviteId, true));
              if (!"READY".equals(row.state())) throw unavailable(false);
              Checked checked = checked(inviteId, row);
              requireToken(inviteId, checked.metadata());
              Invite invite = invite(inviteId);
              if (jdbc.update(
                      "UPDATE protected_invitation_mail_outbox SET state='SENDING',updated_at=?"
                          + " WHERE invite_id=? AND state='READY'",
                      Timestamp.from(clock.instant()),
                      inviteId)
                  != 1) throw unavailable(false);
              return new Claimed(checked.mail(), invite);
            });
    InviteMailSendReceipt receipt;
    try {
      Mail mail = claim.mail();
      Invite invite = claim.invite();
      receipt =
          sender.send(
              invite.recipient(),
              mail.subject(),
              mail.body(),
              mail.acceptUrl(),
              invite.tenant(),
              mail.language(),
              InviteMailOrigin.of(
                  invite.reservation() == null ? invite.tenant() : null, Purpose.ACCOUNT_INVITE));
      if (receipt == null
          || receipt.sentAt() == null
          || !invite.recipient().equals(receipt.recipientEmail())) throw unavailable(false);
    } catch (RuntimeException failure) {
      boolean noEffect = failure instanceof SmtpSendException smtp && smtp.isConfirmedNotSent();
      recordFailure(inviteId, noEffect);
      throw unavailable(noEffect);
    }
    try {
      acknowledgement.executeWithoutResult(
          tx -> {
            requireOwningSql();
            if (jdbc.update(
                    "UPDATE protected_invitation_mail_outbox SET state='SENT',sent_at=?,updated_at=?"
                        + " WHERE invite_id=? AND state='SENDING'",
                    Timestamp.from(receipt.sentAt()),
                    Timestamp.from(clock.instant()),
                    inviteId)
                != 1) throw unavailable(false);
          });
    } catch (RuntimeException uncertainAcknowledgement) {
      // The commit may have succeeded. Never turn this into a second SMTP attempt.
      throw unavailable(false);
    }
    return receipt;
  }

  /** Historical audit data only; this projection cannot prepare or release a mail. */
  public long sentTemplateId(long inviteId) {
    Row row = only(rows(inviteId, false));
    if (!"SENT".equals(row.state()) || row.sentAt() == null) throw unavailable(false);
    return checked(inviteId, row).mail().templateId();
  }

  private void recordFailure(long inviteId, boolean noEffect) {
    try {
      acknowledgement.executeWithoutResult(
          tx -> {
            requireOwningSql();
            jdbc.update(
                "UPDATE protected_invitation_mail_outbox SET state=?,updated_at=?"
                    + " WHERE invite_id=? AND state='SENDING'",
                noEffect ? "FAILED_NO_EFFECT" : "UNKNOWN",
                Timestamp.from(clock.instant()),
                inviteId);
          });
    } catch (RuntimeException ignored) {
      // A retained SENDING row also forbids retransmission after uncertain persistence.
    }
  }

  private InviteMailSendReceipt historical(long inviteId, Row row) {
    if (row.sentAt() == null) throw unavailable(false);
    checked(inviteId, row);
    var frozen = journal.pending(inviteId);
    String recipient =
        json.read(frozen.requestJson(), JsonNode.class).path("scope").path("recipient").asText();
    return new InviteMailSendReceipt(recipient, row.sentAt());
  }

  /** Checks sealed data ownership without turning a historical record into live authority. */
  private Checked checked(long inviteId, Row row) {
    var frozen = journal.pending(inviteId);
    JsonNode scope = json.read(frozen.requestJson(), JsonNode.class).path("scope");
    Metadata metadata = json.read(row.metadata(), Metadata.class);
    if (!row.metadata().equals(json.canonical(metadata))
        || !realmId.equals(metadata.realmId())
        || inviteId != metadata.inviteId()
        || !Long.toString(inviteId).equals(scope.path("inviteId").asText())
        || !row.approvalId().equals(frozen.approvalId().toString())
        || !row.approvalId().equals(metadata.approvalId())
        || !scope.path("workflowId").asText().equals(metadata.workflowId())
        || scope.path("authorizationRevision").longValue() != metadata.authorizationRevision()
        || !frozen.requestDigest().equals(metadata.requestDigest())
        || !savedResponse(inviteId).path("scopeDigest").asText().equals(metadata.scopeDigest()))
      throw unavailable(false);
    String mailJson = secrets.open(metadata, row.sealed());
    // open strictly authenticated the closed six-field payload under its private one-MiB limit.
    // Do not widen the separate 65-KiB HTTP/proof reader to reconstruct local mail data.
    try {
      return new Checked(metadata, mailReader.readValue(mailJson, Mail.class));
    } catch (java.io.IOException | RuntimeException invalidMail) {
      throw unavailable(false);
    }
  }

  private void requireToken(long inviteId, Metadata metadata) {
    Invite current = invite(inviteId);
    if (!"EMAIL_SENT".equals(current.status()) || !metadata.tokenHash().equals(current.tokenHash()))
      throw unavailable(false);
  }

  private JsonNode savedResponse(long inviteId) {
    String response =
        jdbc.queryForObject(
            "SELECT response_json FROM protected_invitation_authorization WHERE invite_id=?",
            String.class,
            inviteId);
    return json.read(response, JsonNode.class);
  }

  private Invite invite(long inviteId) {
    var found =
        jdbc.query(
            "SELECT recipient_email,tenant_id,tenant_id_reservation_token,token_hash,status"
                + " FROM account_invite WHERE id=?",
            (rs, index) ->
                new Invite(
                    rs.getString("recipient_email"),
                    rs.getObject("tenant_id", Long.class),
                    rs.getString("tenant_id_reservation_token"),
                    rs.getString("token_hash"),
                    rs.getString("status")),
            inviteId);
    if (found.size() != 1) throw unavailable(false);
    return found.get(0);
  }

  private java.util.List<Row> rows(long inviteId, boolean lock) {
    return jdbc.query(
        "SELECT approval_id,metadata_json,sealed_mail,state,sent_at"
            + " FROM protected_invitation_mail_outbox WHERE invite_id=?"
            + (lock ? " FOR UPDATE" : ""),
        (rs, index) -> {
          Timestamp sent = rs.getTimestamp("sent_at");
          return new Row(
              rs.getString("approval_id"),
              rs.getString("metadata_json"),
              rs.getString("sealed_mail"),
              rs.getString("state"),
              sent == null ? null : sent.toInstant());
        },
        inviteId);
  }

  private static Row only(java.util.List<Row> rows) {
    if (rows.size() != 1) throw unavailable(false);
    return rows.get(0);
  }

  private void requireOwningSql() {
    var source = jdbc.getDataSource();
    if (source == null || !TransactionSynchronizationManager.isActualTransactionActive())
      throw unavailable(false);
    var connection = DataSourceUtils.getConnection(source);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, source)
          || connection.getAutoCommit()) throw unavailable(false);
    } catch (java.sql.SQLException unavailableConnection) {
      throw unavailable(false);
    } finally {
      DataSourceUtils.releaseConnection(connection, source);
    }
  }

  private static DeliveryUnavailable unavailable(boolean noEffect) {
    return new DeliveryUnavailable(noEffect);
  }

  public static final class DeliveryUnavailable extends RuntimeException {
    private final boolean confirmedNotSent;

    private DeliveryUnavailable(boolean confirmedNotSent) {
      super("Protected invitation mail unavailable");
      this.confirmedNotSent = confirmedNotSent;
    }

    public boolean confirmedNotSent() {
      return confirmedNotSent;
    }
  }

  private record Row(
      String approvalId, String metadata, String sealed, String state, Instant sentAt) {
    @Override
    public String toString() {
      return "ProtectedMailRow[redacted]";
    }
  }

  private record Invite(
      String recipient, Long tenant, String reservation, String tokenHash, String status) {
    @Override
    public String toString() {
      return "ProtectedMailInvite[redacted]";
    }
  }

  private record Checked(Metadata metadata, Mail mail) {
    @Override
    public String toString() {
      return "CheckedProtectedMail[redacted]";
    }
  }

  private record Claimed(Mail mail, Invite invite) {
    @Override
    public String toString() {
      return "ClaimedProtectedMail[redacted]";
    }
  }

  public record Mail(
      String rawToken,
      String acceptUrl,
      String subject,
      String body,
      long templateId,
      String language) {
    @Override
    public String toString() {
      return "ProtectedInvitationMail[redacted]";
    }
  }
}
