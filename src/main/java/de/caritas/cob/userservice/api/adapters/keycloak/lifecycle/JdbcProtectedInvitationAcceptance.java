package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A recipient claim must be durable before creating a disabled protected account. */
public final class JdbcProtectedInvitationAcceptance {
  private final JdbcTemplate jdbc;
  private final JdbcProtectedInvitationJournal journal;
  private final ProtectedInvitationCodec approvals;
  private final AccountLifecycleCodec json;
  private final Clock clock;

  public JdbcProtectedInvitationAcceptance(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationCodec approvals,
      AccountLifecycleCodec json,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    Objects.requireNonNull(journal).requireOwner(jdbc, manager);
    this.jdbc = jdbc;
    this.journal = journal;
    this.approvals = Objects.requireNonNull(approvals);
    this.json = Objects.requireNonNull(json);
    this.clock = Objects.requireNonNull(clock);
  }

  public AcceptanceClaim claim(long inviteId, String tokenHash, JsonNode registration) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || inviteId < 1
        || tokenHash == null
        || !tokenHash.matches("[0-9a-f]{64}")
        || registration == null
        || !registration.isObject()) throw unavailable();
    // Freeze the caller's mutable JSON before acquiring a lock; no password is persisted.
    JsonNode frozenRegistration = json.read(json.canonical(registration), JsonNode.class);
    return journal.withReadyInvite(
        inviteId,
        () -> {
          var invites =
              jdbc.query(
                  "SELECT status,token_hash FROM account_invite WHERE id=?",
                  (rs, index) -> new String[] {rs.getString("status"), rs.getString("token_hash")},
                  inviteId);
          if (invites.size() != 1
              || !"EMAIL_SENT".equals(invites.getFirst()[0])
              || !equal(tokenHash, invites.getFirst()[1])) throw unavailable();
          FrozenApproval frozen = journal.pending(inviteId);
          String response =
              jdbc.queryForObject(
                  "SELECT response_json FROM protected_invitation_authorization WHERE invite_id=? AND state='READY'",
                  String.class,
                  inviteId);
          VerifiedApproval approval = approvals.verifyAuthorized(frozen, response);
          String digest =
              json.digest(
                  "protected-recipient-registration/v1",
                  Map.of(
                      "inviteId",
                      Long.toString(inviteId),
                      "approvalId",
                      frozen.approvalId().toString(),
                      "approvalRequestDigest",
                      frozen.requestDigest(),
                      "registration",
                      frozenRegistration));
          var rows = rows(inviteId);
          if (rows.isEmpty()) {
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update(
                "INSERT INTO protected_invitation_acceptance"
                    + " (invite_id,approval_id,claim_id,attempt_id,execution_id,journal_version,registration_digest,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,1,?,?,?)",
                inviteId,
                frozen.approvalId().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                digest,
                now,
                now);
            rows = rows(inviteId);
          }
          if (rows.size() != 1) throw unavailable();
          var saved = rows.getFirst();
          if (!frozen.approvalId().toString().equals(saved.approvalId())
              || saved.version() != 1
              || !equal(digest, saved.digest())) throw unavailable();
          return new AcceptanceClaim(
              frozen,
              approval,
              uuid(saved.claimId()),
              uuid(saved.attemptId()),
              uuid(saved.executionId()),
              saved.digest());
        });
  }

  private java.util.List<Row> rows(long inviteId) {
    return jdbc.query(
        "SELECT * FROM protected_invitation_acceptance WHERE invite_id=? FOR UPDATE",
        (rs, index) ->
            new Row(
                rs.getString("approval_id"),
                rs.getString("claim_id"),
                rs.getString("attempt_id"),
                rs.getString("execution_id"),
                rs.getLong("journal_version"),
                rs.getString("registration_digest")),
        inviteId);
  }

  private static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) throw unavailable();
      return parsed;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static boolean equal(String expected, String actual) {
    return expected != null
        && actual != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private static AccessDeniedException unavailable() {
    return new AccessDeniedException("Protected recipient acceptance is pending or unavailable");
  }

  private record Row(
      String approvalId,
      String claimId,
      String attemptId,
      String executionId,
      long version,
      String digest) {
    @Override
    public String toString() {
      return "ProtectedAcceptanceRow[redacted]";
    }
  }

  /** Only this SQL owner can mint the native creation handoff. */
  public static final class AcceptanceClaim {
    private final FrozenApproval frozen;
    private final VerifiedApproval approval;
    private final UUID claimId;
    private final UUID attemptId;
    private final UUID executionId;
    private final String registrationDigest;

    private AcceptanceClaim(
        FrozenApproval frozen,
        VerifiedApproval approval,
        UUID claimId,
        UUID attemptId,
        UUID executionId,
        String registrationDigest) {
      this.frozen = frozen;
      this.approval = approval;
      this.claimId = claimId;
      this.attemptId = attemptId;
      this.executionId = executionId;
      this.registrationDigest = registrationDigest;
    }

    public FrozenApproval frozen() {
      return frozen;
    }

    public VerifiedApproval approval() {
      return approval;
    }

    public UUID claimId() {
      return claimId;
    }

    public UUID attemptId() {
      return attemptId;
    }

    public UUID executionId() {
      return executionId;
    }

    public long journalVersion() {
      return 1;
    }

    public String registrationDigest() {
      return registrationDigest;
    }

    @Override
    public String toString() {
      return "ProtectedAcceptanceClaim[redacted]";
    }
  }
}
