package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable original metadata before native RPC; no password, bearer or creation body is stored. */
public final class JdbcProtectedAccountCreation {
  private final JdbcTemplate jdbc;
  private final JdbcProtectedInvitationJournal journal;
  private final ProtectedAccountCodec accounts;
  private final AccountLifecycleCodec json;
  private final Clock clock;

  public JdbcProtectedAccountCreation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedAccountCodec accounts,
      AccountLifecycleCodec json,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    Objects.requireNonNull(journal).requireOwner(jdbc, manager);
    this.jdbc = jdbc;
    this.journal = journal;
    this.accounts = Objects.requireNonNull(accounts);
    this.json = Objects.requireNonNull(json);
    this.clock = Objects.requireNonNull(clock);
  }

  public void prepare(AcceptanceClaim claim, PreparedCreation prepared) {
    outsideSql();
    String checkpoint = accounts.checkpointPrepared(prepared);
    var original = accounts.restorePrepared(claim, checkpoint);
    owned(
        claim,
        () -> {
          var found = rows(claim);
          if (found.isEmpty()) {
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update(
                "INSERT INTO protected_account_creation (invite_id,approval_id,claim_id,attempt_id,execution_id,prepared_checkpoint,creation_fingerprint,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PREPARED',?,?)",
                inviteId(claim),
                claim.frozen().approvalId().toString(),
                claim.claimId().toString(),
                claim.attemptId().toString(),
                claim.executionId().toString(),
                checkpoint,
                original.creationFingerprint(),
                now,
                now);
          } else {
            Row saved = checked(claim, one(found));
            if (!equal(saved.prepared(), checkpoint)
                || !equal(saved.fingerprint(), original.creationFingerprint())) throw unavailable();
          }
          return null;
        });
  }

  public PreparedMetadata metadata(AcceptanceClaim claim) {
    outsideSql();
    return owned(
        claim, () -> accounts.restorePrepared(claim, checked(claim, one(rows(claim))).prepared()));
  }

  public void recordCreated(AcceptanceClaim claim, VerifiedCreated created) {
    outsideSql();
    String checkpoint = accounts.checkpoint(created);
    VerifiedCreated authenticated = accounts.restore(claim, checkpoint);
    owned(
        claim,
        () -> {
          Row saved = checked(claim, one(rows(claim)));
          if (!equal(saved.fingerprint(), authenticated.creationFingerprint())
              || !claim.claimId().equals(authenticated.claimId())
              || !claim.attemptId().equals(authenticated.attemptId())
              || !claim.executionId().equals(authenticated.executionId())) throw unavailable();
          if ("CREATED".equals(saved.state())) {
            if (!equal(saved.created(), checkpoint)
                || !authenticated.accountId().equals(saved.account())) throw unavailable();
          } else if (jdbc.update(
                  "UPDATE protected_account_creation SET account_id=?,created_checkpoint=?,state='CREATED',updated_at=? WHERE invite_id=? AND state='PREPARED' AND account_id IS NULL AND created_checkpoint IS NULL",
                  authenticated.accountId(),
                  checkpoint,
                  Timestamp.from(clock.instant()),
                  inviteId(claim))
              != 1) throw unavailable();
          return null;
        });
  }

  public VerifiedCreated created(AcceptanceClaim claim) {
    outsideSql();
    return owned(
        claim,
        () -> {
          Row saved = checked(claim, one(rows(claim)));
          if (!"CREATED".equals(saved.state())) throw unavailable();
          var restored = accounts.restore(claim, saved.created());
          if (!restored.accountId().equals(saved.account())
              || !equal(restored.creationFingerprint(), saved.fingerprint())) throw unavailable();
          return restored;
        });
  }

  public PreparedRecovery recovery(AcceptanceClaim claim, long expectedGeneration) {
    outsideSql();
    if (expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE)
      throw new IllegalArgumentException("Invalid protected recovery generation");
    return owned(
        claim,
        () -> {
          Row saved = checked(claim, one(rows(claim)));
          var metadata = accounts.restorePrepared(claim, saved.prepared());
          List<String> found =
              jdbc.queryForList(
                  "SELECT recovery_id FROM protected_account_recovery WHERE invite_id=? AND expected_generation=? FOR UPDATE",
                  String.class,
                  inviteId(claim),
                  expectedGeneration);
          UUID id;
          if (found.isEmpty()) {
            id = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO protected_account_recovery (invite_id,expected_generation,recovery_id,created_at) VALUES (?,?,?,?)",
                inviteId(claim),
                expectedGeneration,
                id.toString(),
                Timestamp.from(clock.instant()));
          } else {
            if (found.size() != 1) throw unavailable();
            id = uuid(found.getFirst());
          }
          return accounts.prepareRecovery(metadata, expectedGeneration, id);
        });
  }

  /** Historical SQL evidence can request reducing recovery only, never creation. */
  public PreparedRecovery recovery(
      JdbcProtectedInvitationAcceptance.HistoricalAcceptance historical, long expectedGeneration) {
    throw unavailable();
  }

  private <T> T owned(AcceptanceClaim claim, Supplier<T> action) {
    long invite = inviteId(claim);
    return journal.withRecipientInvite(
        invite,
        () -> {
          if (!claim.frozen().equals(journal.pending(invite))) throw unavailable();
          var row =
              jdbc.queryForMap(
                  "SELECT * FROM protected_invitation_acceptance WHERE invite_id=? FOR UPDATE",
                  invite);
          if (!claim.frozen().approvalId().toString().equals(row.get("approval_id"))
              || !claim.claimId().toString().equals(row.get("claim_id"))
              || !claim.attemptId().toString().equals(row.get("attempt_id"))
              || !claim.executionId().toString().equals(row.get("execution_id"))
              || ((Number) row.get("journal_version")).longValue() != 1
              || !equal(claim.registrationDigest(), (String) row.get("registration_digest")))
            throw unavailable();
          String token =
              jdbc.queryForObject(
                  "SELECT token_hash FROM account_invite WHERE id=?", String.class, invite);
          String response =
              jdbc.queryForObject(
                  "SELECT response_json FROM protected_invitation_authorization WHERE invite_id=? AND state='READY'",
                  String.class,
                  invite);
          if (!equal(token, claim.tokenHash())
              || !equal(
                  json.canonical(json.read(response, JsonNode.class)),
                  claim.approval().responseJson())) throw unavailable();
          return action.get();
        });
  }

  private List<Row> rows(AcceptanceClaim claim) {
    return jdbc.query(
        "SELECT * FROM protected_account_creation WHERE invite_id=? FOR UPDATE",
        (rs, index) ->
            new Row(
                rs.getString("approval_id"),
                rs.getString("claim_id"),
                rs.getString("attempt_id"),
                rs.getString("execution_id"),
                rs.getString("prepared_checkpoint"),
                rs.getString("creation_fingerprint"),
                rs.getString("account_id"),
                rs.getString("created_checkpoint"),
                rs.getString("state")),
        inviteId(claim));
  }

  private Row checked(AcceptanceClaim claim, Row row) {
    if (!claim.frozen().approvalId().toString().equals(row.approval())
        || !claim.claimId().toString().equals(row.claim())
        || !claim.attemptId().toString().equals(row.attempt())
        || !claim.executionId().toString().equals(row.execution())
        || !Set.of("PREPARED", "CREATED").contains(row.state())
        || !equal(
            accounts.restorePrepared(claim, row.prepared()).creationFingerprint(),
            row.fingerprint())) throw unavailable();
    if ("PREPARED".equals(row.state())
        ? row.account() != null || row.created() != null
        : row.account() == null || row.created() == null) throw unavailable();
    return row;
  }

  private static Row one(List<Row> rows) {
    if (rows.size() != 1) throw unavailable();
    return rows.getFirst();
  }

  private long inviteId(AcceptanceClaim claim) {
    if (claim == null
        || claim.frozen() == null
        || claim.approval() == null
        || claim.journalVersion() != 1) throw unavailable();
    String value =
        json.read(claim.frozen().requestJson(), JsonNode.class)
            .path("scope")
            .path("inviteId")
            .asText();
    try {
      long id = Long.parseLong(value);
      if (id < 1 || !Long.toString(id).equals(value)) throw unavailable();
      return id;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw unavailable();
      return id;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Protected account creation unavailable");
  }

  private record Row(
      String approval,
      String claim,
      String attempt,
      String execution,
      String prepared,
      String fingerprint,
      String account,
      String created,
      String state) {
    @Override
    public String toString() {
      return "ProtectedCreationRow[redacted]";
    }
  }
}
