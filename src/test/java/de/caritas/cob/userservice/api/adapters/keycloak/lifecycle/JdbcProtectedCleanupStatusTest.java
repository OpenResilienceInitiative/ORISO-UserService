package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.*;

/** Actual owner SQL/restarts and independent native signed vectors; only fixed GET is doubled. */
class JdbcProtectedCleanupStatusTest {
  private final byte[] key =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private final AccountLifecycleCodec json = new AccountLifecycleCodec(key);
  private final JsonNode account = resource("protected-account-v1.json"),
      vector = resource("protected-cleanup-status-v1.json"),
      recover = resource("protected-account-recovery-v1.json");
  private final Clock before = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC),
      after = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final ProtectedInvitationCodec approvals =
      new ProtectedInvitationCodec(
          key, before, account.path("realmName").asText(), account.path("realmId").asText());
  private final ProtectedAccountCodec accounts =
      new ProtectedAccountCodec(
          key,
          before,
          approvals,
          account.path("realmName").asText(),
          account.path("realmId").asText(),
          account.path("realmIssuer").asText());
  private final ProtectedInvitationRestrictionCodec restrictions =
      new ProtectedInvitationRestrictionCodec(
          approvals,
          json,
          key,
          after,
          account.path("realmId").asText(),
          account.path("realmIssuer").asText());
  private final ProtectedCleanupStatusCodec cleanup =
      new ProtectedCleanupStatusCodec(json, restrictions, accounts, key);
  private final KeycloakProtectedRestrictions receiver = mock(KeycloakProtectedRestrictions.class);
  private final AtomicInteger released = new AtomicInteger();
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private JdbcProtectedInvitationAcceptance acceptance;
  private JdbcProtectedAccountCreation creation;
  private VerifiedRecovery recovered;

  @BeforeEach
  void originalOwnedSqlAndRealNativeProofs() throws Exception {
    var ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(ds);
    manager = new DataSourceTransactionManager(ds);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36),accepted_by_user_id VARCHAR(36),accepted_at TIMESTAMP,provisioning_status VARCHAR(32),update_date TIMESTAMP,active_recipient_key VARCHAR(255))");
    for (String m :
        new String[] {
          "20261022_protected_invitation_367",
          "20261022_protected_invitation_cancel_367",
          "20261022_protected_invitation_acceptance_367",
          "20261022_protected_account_activation_367",
          "20261022_protected_account_creation_367",
          "20261022_protected_invitation_cleanup_status_367"
        })
      for (String sql :
          Files.readString(Path.of("src/main/resources/db/changelog/changeset", m, "migrate.sql"))
              .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    var av = account.path("approvalVector");
    var frozen =
        approvals.freeze(
            UUID.fromString(av.path("approvalId").asText()),
            av.path("request").toString(),
            av.path("issuerRealm").asText(),
            av.path("issuerSubject").asText());
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,tenant_id,recipient_email,purpose,status,expires_at,token_hash,active_recipient_key) VALUES(73,'TENANT_ADMIN',7,'ada@example.test','INVITE','DRAFT',?,?,'still-held')",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")),
        "c".repeat(64));
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, json, before);
    journal.propose(73, frozen);
    journal.recordReady(
        frozen.approvalId(), approvals.verifyAuthorized(frozen, av.path("response").toString()));
    jdbc.update("UPDATE account_invite SET status='EMAIL_SENT' WHERE id=73");
    // Immutable test-vector custody represents the original committed acceptance, never a raw
    // caller factory.
    jdbc.update(
        "INSERT INTO protected_invitation_acceptance(invite_id,approval_id,claim_id,attempt_id,execution_id,journal_version,registration_digest,created_at,updated_at) VALUES(73,?,?,?,?,1,?,?,?)",
        frozen.approvalId().toString(),
        account.path("claimId").asText(),
        account.path("attemptId").asText(),
        account.path("executionId").asText(),
        account.path("registrationDigest").asText(),
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
    acceptance =
        new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, json, after);
    var claim = acceptance.historical(73).claim();
    creation = new JdbcProtectedAccountCreation(jdbc, manager, journal, accounts, json, before);
    var prepared =
        accounts.prepare(
            claim,
            account.path("registration"),
            json.read(account.path("account").toString(), AccountCreation.class));
    creation.prepare(claim, prepared);
    creation.recordCreated(
        claim, accounts.verifyCreated(prepared, bytes(account.path("createdResponse"))));
    jdbc.update(
        "INSERT INTO protected_account_recovery(invite_id,expected_generation,recovery_id,created_at) VALUES(73,1,?,?)",
        recover.path("recoveryId").asText(),
        Timestamp.from(before.instant()));
    recovered =
        accounts.verifyRecovery(creation.recovery(claim, 1), bytes(vector.path("boundRecovery")));
    when(receiver.cancel(any()))
        .thenAnswer(
            call ->
                restrictions.verify(
                    call.getArgument(0), bytes(vector.path("boundRestriction")), true));
    service()
        .cancel(
            73,
            Reason.REVOKED,
            () ->
                jdbc.update(
                    "UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73"));
    assertTrue(service().verifiedRestriction(73).cleanupRequired());
    when(receiver.cleanupStatus(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
              return cleanup.verify(
                  call.getArgument(0),
                  call.getArgument(1),
                  call.getArgument(2),
                  call.getArgument(3),
                  bytes(vector.path("bound")));
            });
  }

  @Test
  void independentGetPersistsSeparateProofAndOnlyThatProofPermitsReleaseAfterRestart() {
    String original =
        jdbc.queryForObject(
            "SELECT response_json FROM protected_invitation_cancellation", String.class);
    assertThrows(
        AccessDeniedException.class,
        () -> service().withAcknowledged(73, () -> released.incrementAndGet()));
    assertDoesNotThrow(() -> service().refreshAfterCleanup(73, recovered, local()));
    assertEquals(
        vector.path("bound"),
        json.read(
            jdbc.queryForObject(
                "SELECT final_status_json FROM protected_invitation_cleanup_status WHERE invite_id=73",
                String.class),
            JsonNode.class));
    assertEquals(
        original,
        jdbc.queryForObject(
            "SELECT response_json FROM protected_invitation_cancellation", String.class));
    assertTrue(service().verifiedRestriction(73).cleanupRequired());
    assertEquals(1, service().withAcknowledged(73, () -> released.incrementAndGet()));
    assertEquals(1, released.get());
    verify(receiver).cleanupStatus(any(), any(), eq(recovered), eq(local()));
  }

  @Test
  void changedFinalProofOrSavedRecoveryAndCallbackRollbackNeverReleaseOwnedReservation() {
    assertDoesNotThrow(() -> service().refreshAfterCleanup(73, recovered, local()));
    String saved =
        jdbc.queryForObject(
            "SELECT final_status_json FROM protected_invitation_cleanup_status", String.class);
    jdbc.update("UPDATE protected_invitation_cleanup_status SET final_status_json='{}'");
    assertThrows(
        RuntimeException.class,
        () -> service().withAcknowledged(73, () -> released.incrementAndGet()));
    jdbc.update("UPDATE protected_invitation_cleanup_status SET final_status_json=?", saved);
    String id = recover.path("recoveryId").asText();
    jdbc.update(
        "UPDATE protected_account_recovery SET recovery_id=?", UUID.randomUUID().toString());
    assertThrows(
        RuntimeException.class,
        () -> service().withAcknowledged(73, () -> released.incrementAndGet()));
    jdbc.update("UPDATE protected_account_recovery SET recovery_id=?", id);
    assertThrows(
        IllegalStateException.class,
        () ->
            service()
                .withAcknowledged(
                    73,
                    () -> {
                      jdbc.update(
                          "UPDATE account_invite SET active_recipient_key=NULL WHERE id=73");
                      throw new IllegalStateException("synthetic local callback rollback");
                    }));
    assertEquals(
        "still-held",
        jdbc.queryForObject("SELECT active_recipient_key FROM account_invite", String.class));
    assertEquals(0, released.get());
    assertEquals(1, service().withAcknowledged(73, () -> released.incrementAndGet()));
  }

  @Test
  void ownerReadsOnlyTheAlreadyPersistedRecoveryAndNeverAllocatesDuringRelease() {
    var history = acceptance.historical(73);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              var saved = assertDoesNotThrow(() -> creation.recoveryWithinOwner(history, 1));
              assertNotNull(saved);
              assertEquals(recovered.executionId(), saved.recoveryId());
            });
    assertThrows(RuntimeException.class,
        () -> new TransactionTemplate(manager).executeWithoutResult(
            tx -> creation.recoveryWithinOwner(history, 2)));
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM protected_account_recovery", Integer.class));
    assertThrows(RuntimeException.class, () -> creation.recoveryWithinOwner(history, 1));
    verify(receiver, never()).cleanupStatus(any(), any(), any(), any());
  }

  @Test
  void alreadyRestrictedPendingHistorySkipsAcceptedExpiryButCommittedLocalRowsRefuse() {
    var history = acceptance.historical(73);
    var activation = mock(JdbcProtectedAccountActivation.class);
    String original =
        jdbc.queryForObject(
            "SELECT response_json FROM protected_invitation_cancellation", String.class);
    clearInvocations(receiver);
    assertDoesNotThrow(() -> service().cancelPendingActivation(history, activation));
    assertEquals(
        original,
        jdbc.queryForObject(
            "SELECT response_json FROM protected_invitation_cancellation", String.class));
    verifyNoInteractions(activation, receiver);
    // An already invalidated original may predate its durable cancellation row after an old crash.
    jdbc.update("DELETE FROM protected_invitation_cancellation WHERE invite_id=73");
    jdbc.update("UPDATE protected_invitation_authorization SET state='READY' WHERE invite_id=73");
    assertDoesNotThrow(() -> service().cancelPendingActivation(history, activation));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM protected_invitation_cancellation", Integer.class));
    verify(receiver).cancel(any());
    verifyNoInteractions(activation);
    clearInvocations(receiver);
    jdbc.update(
        "INSERT INTO protected_account_activation(invite_id,claim_id,attempt_id,execution_id,account_id,state,created_checkpoint,local_effects_json,local_effects_digest,local_outbox_id,created_at,updated_at) VALUES(73,?,?,?,?,'COMMITTED','not-needed-for-closed-guard','{}',?,?,?,?)",
        account.path("claimId").asText(),
        account.path("attemptId").asText(),
        account.path("executionId").asText(),
        account.path("createdResponse").path("accountId").asText(),
        "f".repeat(64),
        UUID.randomUUID().toString(),
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
    assertThrows(
        RuntimeException.class, () -> service().cancelPendingActivation(history, activation));
    verifyNoInteractions(receiver, activation);
  }

  private JdbcProtectedInvitationCancellation service() {
    return new JdbcProtectedInvitationCancellation(
        jdbc,
        manager,
        journal,
        restrictions,
        json,
        receiver,
        id -> {
          throw new AssertionError("Original proposal exists");
        },
        after,
        cleanup,
        accounts,
        acceptance,
        creation);
  }

  private LocalOutcome local() {
    return json.read(account.path("localOutcome").toString(), LocalOutcome.class);
  }

  private byte[] bytes(JsonNode n) {
    return json.canonical(n).getBytes(StandardCharsets.UTF_8);
  }

  private JsonNode resource(String name) {
    try (var in = getClass().getResourceAsStream("/scenario-c/" + name)) {
      return json.read(
          new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8),
          JsonNode.class);
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }
}
