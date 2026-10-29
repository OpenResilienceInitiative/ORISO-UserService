package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real owning SQL and original native receipt vector; no native RPC is simulated here. */
class JdbcProtectedInvitationAcceptanceTest {
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private ProtectedInvitationCodec codec;
  private AccountLifecycleCodec json;
  private FrozenApproval frozen;
  private JsonNode response;
  private JsonNode registration;
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
  private static final String TOKEN_HASH = "c".repeat(64);

  @BeforeEach
  void database() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),"
            + "tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),"
            + "expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36))");
    for (String name :
        new String[] {
          "20261022_protected_invitation_367", "20261022_protected_invitation_acceptance_367"
        }) {
      for (String sql :
          Files.readString(
                  Path.of("src/main/resources/db/changelog/changeset/" + name + "/migrate.sql"))
              .split(";")) {
        if (!sql.isBlank()) jdbc.execute(sql);
      }
    }
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    json = new AccountLifecycleCodec(key);
    codec = new ProtectedInvitationCodec(key, clock, "oriso", "native-synthetic-realm");
    JsonNode vector =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-approval-v1.json")),
            JsonNode.class);
    frozen =
        codec.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            json.canonical(vector.path("request")),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    response = vector.path("response");
    registration =
        json.read(
            "{\"organizationName\":\"Synthetic tenant\",\"password\":\"synthetic-not-a-real-password\"}",
            JsonNode.class);
    jdbc.update(
        "INSERT INTO account_invite VALUES(73,'TENANT_ADMIN',7,'ada@example.test','INVITE','EMAIL_SENT',?,?,NULL,NULL)",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")),
        TOKEN_HASH);
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, codec, json, clock);
    journal.propose(73, frozen);
    journal.recordReady(
        frozen.approvalId(), codec.verifyAuthorized(frozen, json.canonical(response)));
  }

  @Test
  void firstClaimCommitsTheExactApprovalBeforeNativeCreationAndContainsNoPassword() {
    var claim = assertDoesNotThrow(() -> restart().claim(73, TOKEN_HASH, registration));
    assertEquals(frozen, claim.frozen());
    assertEquals(frozen.approvalId(), claim.approval().approvalId());
    assertEquals(1, claim.journalVersion());
    assertNotNull(claim.claimId());
    assertNotNull(claim.attemptId());
    assertNotNull(claim.executionId());
    assertEquals(1, rows());
    assertFalse(
        jdbc.queryForList("SELECT * FROM protected_invitation_acceptance")
            .toString()
            .contains("synthetic-not-a-real-password"));
    assertFalse(claim.toString().contains(TOKEN_HASH));
    assertEquals(
        "EMAIL_SENT", jdbc.queryForObject("SELECT status FROM account_invite", String.class));
  }

  @Test
  void retryAfterRestartKeepsOneClaimAttemptAndExecution() {
    var first = assertDoesNotThrow(() -> restart().claim(73, TOKEN_HASH, registration));
    var retry = assertDoesNotThrow(() -> restart().claim(73, TOKEN_HASH, registration.deepCopy()));
    assertEquals(first.claimId(), retry.claimId());
    assertEquals(first.attemptId(), retry.attemptId());
    assertEquals(first.executionId(), retry.executionId());
    assertEquals(first.registrationDigest(), retry.registrationDigest());
    assertEquals(1, rows());
  }

  @Test
  void aCurrentTokenResumesTheSameDurableClaimWithoutTheOriginalPassword() {
    var first = restart().claim(73, TOKEN_HASH, registration);
    var resumed = restart().resume(73, TOKEN_HASH);
    assertEquals(first.claimId(), resumed.claimId());
    assertEquals(first.attemptId(), resumed.attemptId());
    assertEquals(first.executionId(), resumed.executionId());
    assertEquals(first.registrationDigest(), resumed.registrationDigest());
    assertThrows(RuntimeException.class, () -> restart().resume(73, "d".repeat(64)));
    jdbc.update("UPDATE account_invite SET status='REVOKED' WHERE id=73");
    assertThrows(RuntimeException.class, () -> restart().resume(73, TOKEN_HASH));
  }

  @Test
  void acceptedResumeRequiresTheExactAccountRecordedByTheOwningRegistration() {
    var first = restart().claim(73, TOKEN_HASH, registration);
    jdbc.execute("ALTER TABLE account_invite ADD COLUMN accepted_by_user_id VARCHAR(36)");
    jdbc.execute("ALTER TABLE protected_invitation_acceptance ADD COLUMN account_id VARCHAR(36)");
    String account = "af0907cd-9d2d-4524-a6d8-9c83eb110dad";
    jdbc.update("UPDATE account_invite SET status='ACCEPTED',accepted_by_user_id=?", account);
    jdbc.update("UPDATE protected_invitation_acceptance SET account_id=?", account);
    assertEquals(first.claimId(), restart().resume(73, TOKEN_HASH).claimId());
    var replay = assertDoesNotThrow(() -> restart().claim(73, TOKEN_HASH, registration.deepCopy()));
    assertEquals(first.claimId(), replay.claimId());
    assertEquals(first.attemptId(), replay.attemptId());
    var changed = registration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed)
        .put("password", "changed-synthetic-password");
    assertThrows(RuntimeException.class, () -> restart().claim(73, TOKEN_HASH, changed));
    jdbc.update("UPDATE account_invite SET accepted_by_user_id=?", UUID.randomUUID().toString());
    assertThrows(RuntimeException.class, () -> restart().resume(73, TOKEN_HASH));
    jdbc.update(
        "UPDATE account_invite SET accepted_by_user_id=?,recipient_email='other@example.test'",
        account);
    assertThrows(RuntimeException.class, () -> restart().resume(73, TOKEN_HASH));
  }

  @Test
  void anotherRegistrationCannotReplaceTheFirstClaim() {
    var first = assertDoesNotThrow(() -> restart().claim(73, TOKEN_HASH, registration));
    var changed = registration.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed)
        .put("password", "other-synthetic-password");
    assertThrows(RuntimeException.class, () -> restart().claim(73, TOKEN_HASH, changed));
    assertEquals(first.claimId(), restart().claim(73, TOKEN_HASH, registration).claimId());
    assertEquals(1, rows());
  }

  @Test
  void wrongTokenCannotClaimAnOtherwiseReadyInvitation() {
    assertThrows(RuntimeException.class, () -> restart().claim(73, "d".repeat(64), registration));
    assertEquals(0, rows());
  }

  @Test
  void localRevocationAndReceiptDriftIndependentlyPreventAClaim() {
    jdbc.update("UPDATE account_invite SET status='REVOKED' WHERE id=73");
    assertThrows(RuntimeException.class, () -> restart().claim(73, TOKEN_HASH, registration));
    jdbc.update("UPDATE account_invite SET status='EMAIL_SENT' WHERE id=73");
    jdbc.update("UPDATE protected_invitation_authorization SET response_json='{}'");
    assertThrows(RuntimeException.class, () -> restart().claim(73, TOKEN_HASH, registration));
    assertEquals(0, rows());
  }

  @Test
  void existingClaimDoesNotBypassCurrentRecipientOrTokenDrift() {
    var first = assertDoesNotThrow(() -> restart().claim(73, TOKEN_HASH, registration));
    jdbc.update("UPDATE account_invite SET recipient_email='other@example.test' WHERE id=73");
    assertThrows(RuntimeException.class, () -> restart().claim(73, TOKEN_HASH, registration));
    jdbc.update(
        "UPDATE account_invite SET recipient_email='ada@example.test',token_hash=? WHERE id=73",
        "d".repeat(64));
    assertThrows(RuntimeException.class, () -> restart().claim(73, TOKEN_HASH, registration));
    assertEquals(
        first.claimId().toString(),
        jdbc.queryForObject("SELECT claim_id FROM protected_invitation_acceptance", String.class));
  }

  @Test
  void aClaimCannotBeReturnedFromAnUncommittedOuterTransaction() {
    assertThrows(
        RuntimeException.class,
        () ->
            new TransactionTemplate(manager)
                .execute(tx -> restart().claim(73, TOKEN_HASH, registration)));
    assertEquals(0, rows());
  }

  @Test
  void anotherDataSourceCannotOwnTheClaimTransaction() throws Exception {
    try (var connection = jdbc.getDataSource().getConnection()) {
      var foreign =
          new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new JdbcProtectedInvitationAcceptance(
                  new JdbcTemplate(foreign), manager, journal, codec, json, clock));
    }
    var other = new JdbcDataSource();
    other.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    var otherJdbc = new JdbcTemplate(other);
    var otherManager = new DataSourceTransactionManager(other);
    var otherJournal =
        new JdbcProtectedInvitationJournal(otherJdbc, otherManager, codec, json, clock);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JdbcProtectedInvitationAcceptance(jdbc, manager, otherJournal, codec, json, clock));
    assertEquals(0, rows());
  }

  @Test
  void historicalStatusCustodyRequiresTheExactWritableOwnerAndKeepsErasedTokensNonLive() {
    var first = restart().claim(73, TOKEN_HASH, registration);
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73");
    jdbc.update("UPDATE protected_invitation_authorization SET state='CANCEL_REQUESTED'");
    assertThrows(RuntimeException.class, () -> restart().historicalWithinOwner(73));
    var foreign = new JdbcDataSource();
    foreign.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    var foreignSql = new TransactionTemplate(new DataSourceTransactionManager(foreign));
    assertThrows(
        RuntimeException.class,
        () -> foreignSql.execute(tx -> restart().historicalWithinOwner(73)));
    var readOnly = new TransactionTemplate(manager);
    readOnly.setReadOnly(true);
    assertThrows(
        RuntimeException.class, () -> readOnly.execute(tx -> restart().historicalWithinOwner(73)));
    var owner = new TransactionTemplate(manager);
    var history =
        assertDoesNotThrow(() -> owner.execute(tx -> restart().historicalWithinOwner(73)));
    assertNotNull(history);
    assertEquals(73, history.inviteId());
    assertEquals(first.claimId(), history.claim().claimId());
    assertEquals(first.attemptId(), history.claim().attemptId());
    assertNull(history.claim().tokenHash());
    assertThrows(RuntimeException.class, () -> owner.execute(tx -> restart().historical(73)));
    assertThrows(RuntimeException.class, () -> restart().resume(73, TOKEN_HASH));
    assertEquals(1, rows());
  }

  private JdbcProtectedInvitationAcceptance restart() {
    return new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, codec, json, clock);
  }

  private int rows() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM protected_invitation_acceptance", Integer.class);
  }
}
