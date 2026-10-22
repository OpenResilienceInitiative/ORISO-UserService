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

/** Real SQL transaction seam; native approval and SMTP acceptance remain separate gates. */
class JdbcProtectedInvitationJournalTest {
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private ProtectedInvitationCodec codec;
  private AccountLifecycleCodec json;
  private JsonNode vector;
  private FrozenApproval proposal;
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void database() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY, target_role VARCHAR(64),"
            + " tenant_id BIGINT, recipient_email VARCHAR(255), purpose VARCHAR(32),"
            + " status VARCHAR(32), expires_at TIMESTAMP, token_hash VARCHAR(64), agency_id BIGINT,"
            + " tenant_id_reservation_token VARCHAR(36))");
    for (String sql :
        Files.readString(
                Path.of(
                    "src/main/resources/db/changelog/changeset/"
                        + "20261022_protected_invitation_367/migrate.sql"))
            .split(";")) {
      if (!sql.isBlank()) jdbc.execute(sql);
    }
    jdbc.execute("CREATE TABLE mail_release(id BIGINT PRIMARY KEY)");
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    json = new AccountLifecycleCodec(key);
    codec = new ProtectedInvitationCodec(key, clock, "oriso", "native-synthetic-realm");
    vector =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-approval-v1.json")),
            JsonNode.class);
    proposal =
        codec.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            json.canonical(vector.path("request")),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    jdbc.update(
        "INSERT INTO account_invite VALUES(73,'TENANT_ADMIN',7,'ada@example.test',"
            + "'INVITE','DRAFT',?,NULL,NULL,NULL)",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")));
    journal = restart(clock);
  }

  @Test
  void exactProposalIsDurableBeforeApprovalAndSurvivesRestart() {
    assertEquals(proposal, journal.propose(73, proposal));
    assertEquals(proposal, restart(clock).pending(73));
    assertEquals("APPROVAL_REQUESTED", state());
    assertEquals(0, releases());
  }

  @Test
  void aForeignDatasourceCannotBeTheJournalTransactionOwner() throws Exception {
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      var foreign = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
      assertThrows(IllegalArgumentException.class, () -> new JdbcProtectedInvitationJournal(
          new JdbcTemplate(foreign), manager, codec, json, clock));
    }
  }

  @Test
  void exactRetryDoesNotReplaceStableApprovalOrWorkflow() {
    journal.propose(73, proposal);
    assertEquals(proposal, journal.propose(73, proposal));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM protected_invitation_authorization", Integer.class));
  }

  @Test
  void changedProposalCannotReplaceDurableOriginal() {
    journal.propose(73, proposal);
    var changed =
        codec.freeze(
            UUID.randomUUID(),
            proposal.requestJson(),
            proposal.issuerRealm(),
            proposal.issuerSubject());
    assertThrows(RuntimeException.class, () -> journal.propose(73, changed));
    assertEquals(proposal, journal.pending(73));
  }

  @Test
  void unknownApprovalCannotReleaseUsableMail() {
    journal.propose(73, proposal);
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertEquals(0, releases());
    assertEquals("APPROVAL_REQUESTED", state());
  }

  @Test
  void verifiedReceiptAndMailReleaseUseTheSameLocalTransaction() {
    journal.propose(73, proposal);
    var verified = codec.verifyAuthorized(proposal, json.canonical(vector.path("response")));
    var outer = new TransactionTemplate(manager);
    assertThrows(
        IllegalStateException.class,
        () ->
            outer.execute(
                tx -> {
                  journal.recordReady(proposal.approvalId(), verified);
                  journal.withReadyInvite(73, this::release);
                  throw new IllegalStateException("later local failure");
                }));
    assertEquals("APPROVAL_REQUESTED", state());
    assertEquals(0, releases());
    journal.recordReady(proposal.approvalId(), verified);
    assertEquals(73L, journal.withReadyInvite(73, this::release));
    assertEquals("READY", state());
    assertEquals(1, releases());
  }

  @Test
  void changedActualInviteCannotAcknowledgeTheOldReceipt() {
    journal.propose(73, proposal);
    jdbc.update("UPDATE account_invite SET tenant_id=8 WHERE id=73");
    assertThrows(
        RuntimeException.class,
        () ->
            journal.recordReady(
                proposal.approvalId(),
                codec.verifyAuthorized(proposal, json.canonical(vector.path("response")))));
    assertEquals("APPROVAL_REQUESTED", state());
    assertEquals(0, releases());
  }

  @Test
  void changedRecipientOrRoleCannotUseAStoredReadyReceipt() {
    ready();
    jdbc.update(
        "UPDATE account_invite SET recipient_email='other@example.test', target_role='PLATFORM_ADMIN' WHERE id=73");
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertEquals(0, releases());
  }

  @Test
  void originalExpiryIsNotExtendedByAStoredReceiptOrLocalEdit() {
    ready();
    var later = Clock.fixed(Instant.parse("2030-01-02T03:04:05Z"), ZoneOffset.UTC);
    assertThrows(RuntimeException.class, () -> restart(later).withReadyInvite(73, this::release));
    jdbc.update(
        "UPDATE account_invite SET expires_at=? WHERE id=73",
        Timestamp.from(Instant.parse("2031-01-02T03:04:05Z")));
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertEquals(0, releases());
  }

  @Test
  void cancellationIntentAndLocalRevocationCommitTogetherAndBlockDelayedReceipt() {
    ready();
    journal.cancel(
        73,
        () ->
            jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73"));
    assertEquals("CANCEL_REQUESTED", state());
    assertEquals(
        "REVOKED",
        jdbc.queryForObject("SELECT status FROM account_invite WHERE id=73", String.class));
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertThrows(
        RuntimeException.class,
        () ->
            journal.recordReady(
                proposal.approvalId(),
                codec.verifyAuthorized(proposal, json.canonical(vector.path("response")))));
    assertEquals(0, releases());
  }

  @Test
  void failedLocalCancellationDoesNotCommitItsOutboxIntent() {
    ready();
    assertThrows(
        IllegalStateException.class,
        () ->
            journal.cancel(
                73,
                () -> {
                  jdbc.update("UPDATE account_invite SET status='REVOKED' WHERE id=73");
                  throw new IllegalStateException("local revoke failed");
                }));
    assertEquals("READY", state());
    assertEquals(
        "DRAFT",
        jdbc.queryForObject("SELECT status FROM account_invite WHERE id=73", String.class));
  }

  private void ready() {
    journal.propose(73, proposal);
    journal.recordReady(
        proposal.approvalId(),
        codec.verifyAuthorized(proposal, json.canonical(vector.path("response"))));
    assertEquals("READY", state());
  }

  @Test
  void reservedTenantAndAgencyUseTheOriginalHeldCapability() throws Exception {
    reserved();
    ready();
    assertEquals(73L, journal.withReadyInvite(73, this::release));
    assertEquals(1, releases());
  }

  @Test
  void anotherReservationCapabilityCannotBeProposedForTheSameTenant() throws Exception {
    reserved();
    jdbc.update(
        "UPDATE account_invite SET tenant_id_reservation_token=? WHERE id=73",
        UUID.randomUUID().toString());
    assertThrows(RuntimeException.class, () -> journal.propose(73, proposal));
    assertEquals("ABSENT", state());
    assertEquals(0, releases());
  }

  @Test
  void reservationReplacementCannotReuseAnAcknowledgedReceipt() throws Exception {
    reserved();
    ready();
    jdbc.update(
        "UPDATE account_invite SET tenant_id_reservation_token=? WHERE id=73",
        UUID.randomUUID().toString());
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertEquals(0, releases());
  }

  @Test
  void agencyReplacementCannotReuseAnAcknowledgedReceipt() throws Exception {
    reserved();
    ready();
    jdbc.update("UPDATE account_invite SET agency_id=12 WHERE id=73");
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertEquals(0, releases());
  }

  @Test
  void eachChangedCurrentInvitationFieldIndependentlyBlocksMail() {
    ready();
    String[] changes = {
      "purpose='EXISTING_ACCOUNT_SETUP'",
      "status='ACCEPTED'",
      "recipient_email='other@example.test'",
      "target_role='PLATFORM_ADMIN'"
    };
    for (String change : changes) {
      jdbc.update("UPDATE account_invite SET " + change + " WHERE id=73");
      assertThrows(
          RuntimeException.class, () -> journal.withReadyInvite(73, this::release), change);
      jdbc.update(
          "UPDATE account_invite SET purpose='INVITE',status='DRAFT',"
              + "recipient_email='ada@example.test',target_role='TENANT_ADMIN' WHERE id=73");
    }
    assertEquals(0, releases());
    assertEquals(73L, journal.withReadyInvite(73, this::release));
  }

  @Test
  void anotherInviteIdCannotAcquireThisRowsApproval() {
    var request =
        (com.fasterxml.jackson.databind.node.ObjectNode) vector.path("request").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) request.path("scope")).put("inviteId", "74");
    var foreign =
        codec.freeze(
            proposal.approvalId(),
            json.canonical(request),
            proposal.issuerRealm(),
            proposal.issuerSubject());
    assertThrows(RuntimeException.class, () -> journal.propose(73, foreign));
    assertEquals("ABSENT", state());
  }

  @Test
  void uncommittedInvitationCannotBeDispatchedThroughANewNestedTransaction() {
    var outer = new TransactionTemplate(manager);
    assertThrows(RuntimeException.class, () -> outer.execute(tx -> journal.propose(73, proposal)));
    assertEquals("ABSENT", state());
  }

  @Test
  void failedMailPreparationRollsBackItsLocalEffectAndKeepsTheOriginalApproval() {
    ready();
    assertThrows(
        IllegalStateException.class,
        () ->
            journal.withReadyInvite(
                73,
                () -> {
                  release();
                  throw new IllegalStateException("mail preparation failed");
                }));
    assertEquals(0, releases());
    assertEquals("READY", state());
    assertEquals(73L, journal.withReadyInvite(73, this::release));
  }

  @Test
  void alteredPersistedProposalDigestCannotTurnCachedReadyIntoAuthority() {
    ready();
    jdbc.update(
        "UPDATE protected_invitation_authorization SET request_digest=? WHERE invite_id=73",
        "b".repeat(64));
    assertThrows(RuntimeException.class, () -> journal.withReadyInvite(73, this::release));
    assertEquals(0, releases());
  }

  private void reserved() throws Exception {
    vector =
        json.read(
            Files.readString(
                Path.of("src/test/resources/scenario-c/protected-approval-reserved-v1.json")),
            JsonNode.class);
    proposal =
        codec.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            json.canonical(vector.path("request")),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    jdbc.update(
        "UPDATE account_invite SET tenant_id_reservation_token=?,agency_id=11 WHERE id=73",
        vector.path("syntheticReservationToken").asText());
  }

  private JdbcProtectedInvitationJournal restart(Clock selectedClock) {
    return new JdbcProtectedInvitationJournal(
        jdbc,
        manager,
        new ProtectedInvitationCodec(
            HexFormat.of()
                .parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
            selectedClock,
            "oriso",
            "native-synthetic-realm"),
        json,
        selectedClock);
  }

  private long release() {
    jdbc.update("INSERT INTO mail_release VALUES (73)");
    return 73L;
  }

  private int releases() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM mail_release", Integer.class);
  }

  private String state() {
    var states =
        jdbc.queryForList(
            "SELECT state FROM protected_invitation_authorization WHERE invite_id=73",
            String.class);
    return states.isEmpty() ? "ABSENT" : states.get(0);
  }
}
