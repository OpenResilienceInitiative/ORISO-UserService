package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual SQL/outside-transaction SMTP seam; full native and JPA pairing remain separate. */
class ProtectedInvitationMailOutboxTest {
  private final byte[] key =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private AccountLifecycleCodec json;
  private ProtectedInvitationCodec codec;
  private FrozenApproval proposal;
  private JsonNode approval;
  private ProtectedInvitationMailOutbox.Mail mail;
  private KeycloakProtectedInvitations approvals;
  private InviteMailDispatchService sender;
  private ProtectedInvitationMailOutbox outbox;
  private final AtomicInteger acknowledgementFault = new AtomicInteger();
  private final AtomicInteger observedAcknowledgementFaults = new AtomicInteger();

  @BeforeEach
  void actualDatabaseAndApprovedInvitation() throws Exception {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
    jdbc = new JdbcTemplate(source);
    manager =
        new DataSourceTransactionManager(source) {
          @Override
          protected void doCommit(DefaultTransactionStatus status) {
            int fault = acknowledgementFault.getAndSet(0);
            if (fault == 1) {
              observedAcknowledgementFaults.incrementAndGet();
              throw new TransactionSystemException("synthetic acknowledgement commit refused");
            }
            super.doCommit(status);
            if (fault == 2) {
              observedAcknowledgementFaults.incrementAndGet();
              throw new TransactionSystemException(
                  "synthetic committed acknowledgement reply lost");
            }
          }
        };
    manager.setRollbackOnCommitFailure(true);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36))");
    migrate("20261022_protected_invitation_367");
    migrate("20261022_protected_invitation_mail_367");
    json = new AccountLifecycleCodec(key);
    codec = new ProtectedInvitationCodec(key, clock, "oriso", "native-synthetic-realm");
    approval =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-approval-v1.json")),
            JsonNode.class);
    proposal =
        codec.freeze(
            UUID.fromString(approval.path("approvalId").asText()),
            json.canonical(approval.path("request")),
            approval.path("issuerRealm").asText(),
            approval.path("issuerSubject").asText());
    jdbc.update(
        "INSERT INTO account_invite VALUES(73,'TENANT_ADMIN',7,'ada@example.test','INVITE','DRAFT',?,NULL,NULL,NULL)",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")));
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, codec, json, clock);
    journal.propose(73, proposal);
    var verified = codec.verifyAuthorized(proposal, json.canonical(approval.path("response")));
    journal.recordReady(proposal.approvalId(), verified);
    var payload =
        json.read(
                Files.readString(Path.of("src/test/resources/scenario-c/protected-mail-v1.json")),
                JsonNode.class)
            .path("mail");
    mail = json.read(json.canonical(payload), ProtectedInvitationMailOutbox.Mail.class);
    approvals = mock(KeycloakProtectedInvitations.class);
    sender = mock(InviteMailDispatchService.class);
    when(approvals.reconcile(proposal)).thenReturn(verified);
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("ada@example.test", clock.instant()));
    outbox = restart(clock);
  }

  @Test
  void tokenAndEncryptedMailCommitTogetherAndRestartKeepsTheExactSnapshot() {
    enqueue();
    assertEquals("READY", state());
    String sealed = saved("sealed_mail");
    assertThrows(RuntimeException.class, () -> outbox.sentTemplateId(73));
    assertFalse(sealed.contains(mail.rawToken()));
    assertFalse(sealed.contains(mail.acceptUrl()));
    assertFalse(sealed.contains(mail.body()));
    assertFalse(saved("metadata_json").contains(mail.rawToken()));
    assertEquals(
        hash(),
        jdbc.queryForObject("SELECT token_hash FROM account_invite WHERE id=73", String.class));
    assertEquals(
        new InviteMailSendReceipt("ada@example.test", clock.instant()),
        restart(clock).dispatch(73));
    verify(sender)
        .send(
            eq("ada@example.test"),
            eq(mail.subject()),
            eq(mail.body()),
            eq(mail.acceptUrl()),
            eq(7L),
            eq(mail.language()),
            any());
    assertEquals(sealed, saved("sealed_mail"));
    assertEquals("SENT", state());
    assertEquals(mail.templateId(), restart(clock).sentTemplateId(73));
  }

  @Test
  void aForeignConnectionCannotMasqueradeAsTheMailOwningTransaction() throws Exception {
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      var foreign =
          new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
      new TransactionTemplate(manager)
          .executeWithoutResult(
              tx -> {
                var bound =
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(foreign);
                assertTrue(
                    org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(
                        bound, foreign));
                try {
                  assertFalse(bound.getAutoCommit());
                  assertEquals(
                      1, new JdbcTemplate(foreign).queryForObject("SELECT 1", Integer.class));
                } catch (java.sql.SQLException failure) {
                  throw new IllegalStateException(failure);
                } finally {
                  org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                      bound, foreign);
                }
              });
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ProtectedInvitationMailOutbox(
                  new JdbcTemplate(foreign),
                  manager,
                  journal,
                  json,
                  key,
                  "native-synthetic-realm",
                  clock,
                  approvals,
                  sender));
    }
  }

  @Test
  void boundedLargeUtf8MailReachesTheSenderWithoutWideningTheProofReader() {
    mail =
        new ProtectedInvitationMailOutbox.Mail(
            mail.rawToken(),
            mail.acceptUrl(),
            mail.subject(),
            "Ä".repeat(40_000),
            mail.templateId(),
            null);
    String snapshot = json.canonical(mail);
    assertTrue(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536);
    assertThrows(IllegalArgumentException.class, () -> json.read(snapshot, JsonNode.class));
    enqueue();
    assertEquals("READY", state());
    assertNotNull(assertDoesNotThrow(() -> restart(clock).dispatch(73)));
    verify(sender)
        .send(
            eq("ada@example.test"),
            eq(mail.subject()),
            eq(mail.body()),
            eq(mail.acceptUrl()),
            eq(7L),
            isNull(),
            any());
    assertEquals("SENT", state());
  }

  @Test
  void aRolledBackOwningTransactionLeavesNeitherTokenNorMail() {
    var transaction = new TransactionTemplate(manager);
    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.executeWithoutResult(
                tx -> {
                  enqueue();
                  assertEquals("READY", state());
                  throw new IllegalStateException("synthetic local rollback");
                }));
    assertEquals("ABSENT", state());
    assertNull(
        jdbc.queryForObject("SELECT token_hash FROM account_invite WHERE id=73", String.class));
    verifyNoInteractions(sender, approvals);
  }

  @Test
  void exactQueueRetryNeverMintsANewTokenOrReplacesCiphertext() {
    enqueue();
    assertEquals("READY", state());
    String sealed = saved("sealed_mail");
    AtomicInteger regenerated = new AtomicInteger();
    outbox.enqueue(
        73,
        () -> {
          regenerated.incrementAndGet();
          return mail;
        });
    assertEquals(0, regenerated.get());
    assertEquals(sealed, saved("sealed_mail"));
  }

  @Test
  void actualSmtpSeesCommittedSendingAndNoLocalTransaction() {
    enqueue();
    assertEquals("READY", state());
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("SENDING", state());
              return new InviteMailSendReceipt("ada@example.test", clock.instant());
            });
    assertNotNull(outbox.dispatch(73));
    assertEquals("SENT", state());
  }

  @Test
  void unknownSmtpOutcomeNeverAutoResends() {
    enqueue();
    assertEquals("READY", state());
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenThrow(new IllegalStateException("synthetic transport unknown"));
    assertThrows(RuntimeException.class, () -> outbox.dispatch(73));
    assertTrue(java.util.Set.of("SENDING", "UNKNOWN").contains(state()));
    assertThrows(RuntimeException.class, () -> restart(clock).dispatch(73));
    verify(sender, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void failedOrLostLocalAcknowledgementNeverRepeatsAcceptedSmtp(int fault) {
    enqueue();
    assertEquals("READY", state());
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenAnswer(
            call -> {
              assertEquals("SENDING", state());
              acknowledgementFault.set(fault);
              return new InviteMailSendReceipt("ada@example.test", clock.instant());
            });
    try {
      outbox.dispatch(73);
    } catch (RuntimeException uncertainAcknowledgement) {
      // The authoritative committed SQL state, rather than this local exception, decides recovery.
    }
    assertEquals(1, observedAcknowledgementFaults.get());
    if (fault == 1) {
      assertTrue(java.util.Set.of("SENDING", "UNKNOWN").contains(state()));
      assertThrows(RuntimeException.class, () -> restart(clock).dispatch(73));
    } else {
      assertEquals("SENT", state());
      assertEquals(
          new InviteMailSendReceipt("ada@example.test", clock.instant()),
          restart(clock).dispatch(73));
    }
    verify(sender, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any());
  }

  @Test
  void aReceiptForAnotherRecipientIsNeverRecordedAsSentOrRetried() {
    enqueue();
    assertEquals("READY", state());
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new InviteMailSendReceipt("foreign@example.test", clock.instant()));
    assertThrows(RuntimeException.class, () -> outbox.dispatch(73));
    assertTrue(java.util.Set.of("SENDING", "UNKNOWN").contains(state()));
    assertThrows(RuntimeException.class, () -> restart(clock).dispatch(73));
    verify(sender, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any());
  }

  @Test
  void confirmedNoEffectKeepsItsDistinctOutcomeAndDoesNotAutomaticallyResend() {
    enqueue();
    assertEquals("READY", state());
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenThrow(
            new SmtpSendException(
                SmtpSendException.Category.SMTP_TRANSPORT_FAILED,
                SmtpSendException.DeliveryDisposition.CONFIRMED_NOT_SENT,
                "synthetic refused before transport",
                null));
    assertThrows(RuntimeException.class, () -> outbox.dispatch(73));
    assertEquals("FAILED_NO_EFFECT", state());
    assertThrows(RuntimeException.class, () -> restart(clock).dispatch(73));
    verify(sender, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any());
  }

  @Test
  void historicalSentReceiptSurvivesExpiryAndCancellationWithoutAnotherRpc() {
    enqueue();
    assertEquals("READY", state());
    var receipt = outbox.dispatch(73);
    assertNotNull(receipt);
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73");
    clearInvocations(approvals, sender);
    assertEquals(
        receipt,
        restart(Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC)).dispatch(73));
    verifyNoInteractions(approvals, sender);
  }

  @Test
  void cancellationExpiryAndChangedCurrentTokenPreventSmtp() {
    enqueue();
    assertEquals("READY", state());
    jdbc.update("UPDATE account_invite SET token_hash=? WHERE id=73", "b".repeat(64));
    assertThrows(RuntimeException.class, () -> outbox.dispatch(73));
    jdbc.update("UPDATE account_invite SET token_hash=?,status='REVOKED' WHERE id=73", hash());
    assertThrows(RuntimeException.class, () -> outbox.dispatch(73));
    jdbc.update("UPDATE account_invite SET status='EMAIL_SENT' WHERE id=73");
    assertThrows(
        RuntimeException.class,
        () ->
            restart(Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC))
                .dispatch(73));
    verifyNoInteractions(sender);
  }

  @Test
  void nativeUnknownAndMissingApprovalCannotReleaseUsableMail() {
    enqueue();
    assertEquals("READY", state());
    when(approvals.reconcile(proposal))
        .thenThrow(new KeycloakProtectedInvitations.RemoteFailure(0));
    assertThrows(RuntimeException.class, () -> outbox.dispatch(73));
    assertEquals("READY", state());
    verifyNoInteractions(sender);
    jdbc.update(
        "UPDATE protected_invitation_authorization SET state='APPROVAL_REQUESTED',response_json=NULL WHERE invite_id=73");
    assertThrows(RuntimeException.class, () -> outbox.enqueue(73, () -> mail));
  }

  @Test
  void concurrentDispatchersHaveOnlyOneSmtpWinner() throws Exception {
    enqueue();
    assertEquals("READY", state());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(sender.send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return new InviteMailSendReceipt("ada@example.test", clock.instant());
            });
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(() -> outbox.dispatch(73));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertThrows(RuntimeException.class, () -> restart(clock).dispatch(73));
      release.countDown();
      assertNotNull(first.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    verify(sender, times(1))
        .send(anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any());
  }

  @Test
  void mailTextIsRedactedAndNetworkDispatchRefusesAmbientTransactions() {
    assertEquals("ProtectedInvitationMail[redacted]", mail.toString());
    var transaction = new TransactionTemplate(manager);
    assertThrows(
        RuntimeException.class, () -> transaction.executeWithoutResult(tx -> outbox.dispatch(73)));
    verifyNoInteractions(approvals, sender);
  }

  private void enqueue() {
    outbox.enqueue(
        73,
        () -> {
          jdbc.update(
              "UPDATE account_invite SET token_hash=?,status='EMAIL_SENT' WHERE id=73", hash());
          return mail;
        });
  }

  private String hash() {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(mail.rawToken().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private ProtectedInvitationMailOutbox restart(Clock selected) {
    var selectedCodec =
        new ProtectedInvitationCodec(key, selected, "oriso", "native-synthetic-realm");
    var selectedJournal =
        new JdbcProtectedInvitationJournal(jdbc, manager, selectedCodec, json, selected);
    return new ProtectedInvitationMailOutbox(
        jdbc,
        manager,
        selectedJournal,
        json,
        key,
        "native-synthetic-realm",
        selected,
        approvals,
        sender);
  }

  private String state() {
    var rows =
        jdbc.queryForList(
            "SELECT state FROM protected_invitation_mail_outbox WHERE invite_id=73", String.class);
    return rows.isEmpty() ? "ABSENT" : rows.get(0);
  }

  private String saved(String column) {
    if (!java.util.Set.of("sealed_mail", "metadata_json").contains(column))
      throw new IllegalArgumentException();
    return jdbc.queryForObject(
        "SELECT " + column + " FROM protected_invitation_mail_outbox WHERE invite_id=73",
        String.class);
  }

  private void migrate(String migration) throws Exception {
    for (String sql :
        Files.readString(
                Path.of("src/main/resources/db/changelog/changeset/" + migration + "/migrate.sql"))
            .split(";")) {
      if (!sql.isBlank()) jdbc.execute(sql);
    }
  }
}
