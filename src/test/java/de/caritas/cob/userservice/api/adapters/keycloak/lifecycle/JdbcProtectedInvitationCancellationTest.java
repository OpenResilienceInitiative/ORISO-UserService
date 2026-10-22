package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL/migrations/restarts and receipt MACs; the fixed native transport is a test double. */
class JdbcProtectedInvitationCancellationTest {
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private AccountLifecycleCodec json;
  private ProtectedInvitationRestrictionCodec restrictions;
  private FrozenApproval proposal;
  private JsonNode terminals;
  private final KeycloakProtectedRestrictions receiver = mock(KeycloakProtectedRestrictions.class);
  private final Clock before = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final Clock after = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
  private final AtomicInteger releases = new AtomicInteger();

  @BeforeEach
  void database() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36))");
    for (String migration :
        new String[] {
          "20261022_protected_invitation_367", "20261022_protected_invitation_cancel_367"
        })
      for (String sql :
          Files.readString(
                  Path.of("src/main/resources/db/changelog/changeset", migration, "migrate.sql"))
              .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    json = new AccountLifecycleCodec(key);
    var vector =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-approval-v1.json")),
            JsonNode.class);
    terminals =
        json.read(
            Files.readString(
                Path.of("src/test/resources/scenario-c/protected-restriction-v1.json")),
            JsonNode.class);
    var codec =
        new ProtectedInvitationCodec(key, before, "synthetic", vector.path("issuerRealm").asText());
    proposal =
        codec.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            vector.path("request").toString(),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    restrictions =
        new ProtectedInvitationRestrictionCodec(
            codec,
            json,
            key,
            after,
            vector.path("issuerRealm").asText(),
            "https://auth.example.test/realms/synthetic");
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, codec, json, before);
    jdbc.update(
        "INSERT INTO account_invite VALUES(73,'TENANT_ADMIN',7,'ada@example.test','INVITE','DRAFT',?,NULL,NULL,NULL)",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")));
    journal.propose(73, proposal);
    journal.recordReady(
        proposal.approvalId(),
        codec.verifyAuthorized(proposal, vector.path("response").toString()));
  }

  @Test
  void invalidationAndExactIntentCommitBeforeRpcAndAnUnknownReplyKeepsReleaseClosed() {
    when(receiver.cancel(any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals("REVOKED", status());
              assertEquals("CANCEL_REQUESTED", authorization());
              assertEquals("PENDING", state());
              throw new KeycloakProtectedInvitations.RemoteFailure(0);
            });
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () -> service().cancel(73, Reason.REVOKED, this::invalidate));
    assertEquals("REVOKED", status());
    assertEquals("PENDING", state());
    assertThrows(AccessDeniedException.class, () -> service().withAcknowledged(73, this::release));
    assertEquals(0, releases.get());
  }

  @Test
  void processRestartReusesTheOriginalFrozenCancellationAndNeverCreatesAnotherId() {
    var first = new AtomicReference<FrozenCancellation>();
    when(receiver.cancel(any()))
        .thenAnswer(
            call -> {
              FrozenCancellation f = call.getArgument(0);
              first.set(f);
              throw new KeycloakProtectedInvitations.RemoteFailure(0);
            });
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () -> service().cancel(73, Reason.REVOKED, this::invalidate));
    doAnswer(
            call -> {
              FrozenCancellation f = call.getArgument(0);
              assertEquals(first.get(), f);
              return expired(f);
            })
        .when(receiver)
        .cancel(any());
    service()
        .cancel(
            73, Reason.REVOKED, () -> fail("A saved intent already invalidated the local invite"));
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    assertEquals(1, service().withAcknowledged(73, this::release));
    verify(receiver, times(2)).cancel(any());
  }

  @Test
  void aNativeExpired409RequiresTheExplicitFixedStatusReceiptBeforeRelease() {
    when(receiver.cancel(any())).thenThrow(new KeycloakProtectedInvitations.RemoteFailure(409));
    when(receiver.reconcile(any())).thenAnswer(call -> expired(call.getArgument(0)));
    service().cancel(73, Reason.REVOKED, this::invalidate);
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    assertEquals(0, releases.get());
    assertEquals(1, service().withAcknowledged(73, this::release));
    verify(receiver).reconcile(any());
  }

  @Test
  void noSavedNativeAcknowledgementMeansNoReservationReleaseOrTransfer() {
    assertThrows(AccessDeniedException.class, () -> service().verifiedRestriction(73));
    assertThrows(AccessDeniedException.class, () -> service().withAcknowledged(73, this::release));
    assertEquals(0, releases.get());
    verifyNoInteractions(receiver);
  }

  @Test
  void aBoundReceiverFenceCannotStandInForOwnedAccountCleanup() {
    when(receiver.cancel(any()))
        .thenAnswer(
            call -> {
              var f = (FrozenCancellation) call.getArgument(0);
              var ack =
                  restrictions.verify(
                      f,
                      terminals
                          .path("expiredBound")
                          .toString()
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      true);
              assertTrue(ack.cleanupRequired());
              return ack;
            });
    service().cancel(73, Reason.REVOKED, this::invalidate);
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    var savedFence = assertDoesNotThrow(() -> service().verifiedRestriction(73));
    assertTrue(savedFence.cleanupRequired());
    assertEquals(
        terminals.path("expiredBound"), json.read(savedFence.responseJson(), JsonNode.class));
    assertThrows(AccessDeniedException.class, () -> service().withAcknowledged(73, this::release));
    assertEquals(0, releases.get());
  }

  @Test
  void localInvalidationFailureRollsBackBothIntentAndAuthorizationBeforeAnyRpc() {
    assertThrows(
        IllegalStateException.class,
        () ->
            service()
                .cancel(
                    73,
                    Reason.REVOKED,
                    () -> {
                      invalidate();
                      throw new IllegalStateException("synthetic local rollback");
                    }));
    assertEquals("DRAFT", status());
    assertEquals("READY", authorization());
    assertEquals(0, intents());
    verifyNoInteractions(receiver);
  }

  @Test
  void cancellationCannotHideItsDurableBoundaryInsideAnAmbientTransaction() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(manager)
                .execute(
                    tx -> {
                      service().cancel(73, Reason.REVOKED, this::invalidate);
                      return null;
                    }));
    assertEquals("DRAFT", status());
    assertEquals(0, intents());
    verifyNoInteractions(receiver);
  }

  @Test
  void aCommittedDraftWithoutAProposalGetsOnlyTheOriginalPureRestrictionProposal() {
    jdbc.update("DELETE FROM protected_invitation_authorization WHERE invite_id=73");
    var calls = new AtomicInteger();
    Function<Long, FrozenApproval> missing =
        id -> {
          assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
          assertEquals(73L, id);
          calls.incrementAndGet();
          return proposal;
        };
    when(receiver.cancel(any())).thenThrow(new KeycloakProtectedInvitations.RemoteFailure(0));
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () -> service(jdbc, missing).cancel(73, Reason.REVOKED, this::invalidate));
    assertEquals(1, calls.get());
    assertEquals(proposal, journal.pending(73));
    assertEquals("PENDING", state());
    assertEquals("REVOKED", status());
  }

  @Test
  void anAcknowledgedRetryDoesNotCallTheReceiverOrRepeatLocalInvalidation() {
    when(receiver.cancel(any())).thenAnswer(call -> expired(call.getArgument(0)));
    service().cancel(73, Reason.REVOKED, this::invalidate);
    service().cancel(73, Reason.REVOKED, () -> fail("No repeated local invalidation"));
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    verify(receiver).cancel(any());
  }

  @Test
  void aForgedPersistedAcknowledgementCannotOpenTheReleaseGate() {
    when(receiver.cancel(any())).thenAnswer(call -> expired(call.getArgument(0)));
    service().cancel(73, Reason.REVOKED, this::invalidate);
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    jdbc.update(
        "UPDATE protected_invitation_cancellation SET response_json='{}' WHERE invite_id=73");
    assertThrows(AccessDeniedException.class, () -> service().verifiedRestriction(73));
    assertThrows(AccessDeniedException.class, () -> service().withAcknowledged(73, this::release));
    assertEquals(0, releases.get());
  }

  @Test
  void releaseSharesTheOwningSqlTransactionAndRollsBackOnLocalFailure() {
    when(receiver.cancel(any())).thenAnswer(call -> expired(call.getArgument(0)));
    service().cancel(73, Reason.REVOKED, this::invalidate);
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    assertThrows(
        IllegalStateException.class,
        () ->
            service()
                .withAcknowledged(
                    73,
                    () -> {
                      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                      jdbc.update(
                          "UPDATE account_invite SET tenant_id_reservation_token='synthetic' WHERE id=73");
                      throw new IllegalStateException("synthetic release rollback");
                    }));
    assertNull(
        jdbc.queryForObject(
            "SELECT tenant_id_reservation_token FROM account_invite WHERE id=73", String.class));
    assertEquals("ACKNOWLEDGED", state());
  }

  @Test
  void aForeignDatasourceCannotPretendToOwnRestrictionAcknowledgements() throws Exception {
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      var foreign =
          new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
      assertThrows(
          IllegalArgumentException.class, () -> service(new JdbcTemplate(foreign), id -> proposal));
    }
  }

  @Test
  void localTerminalInvitesWithoutIntentRemainDiscoverableWhileAcknowledgedHistoryIsExcluded() {
    when(receiver.cancel(any())).thenAnswer(call -> expired(call.getArgument(0)));
    service().cancel(73, Reason.REVOKED, this::invalidate);
    assertEquals(1, intents());
    assertEquals("ACKNOWLEDGED", state());
    pendingFixture();
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(74,'COUNSELLOR','INVITE','EXPIRED',?,?)",
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(75,'PLATFORM_ADMIN','INVITE','EXPIRED',?,?)",
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(76,'TENANT_ADMIN','INVITE','ACCEPTED',?,?)",
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
    assertEquals(java.util.List.of(75L), service().pendingInviteIds(100));
    assertEquals(0, releases.get());
    verify(receiver).cancel(any());
  }

  @Test
  void unknownRestrictionsMoveBehindOlderMissingIntentsAndTheRecoveryPageIsBounded() {
    when(receiver.cancel(any())).thenThrow(new KeycloakProtectedInvitations.RemoteFailure(0));
    assertThrows(
        KeycloakProtectedInvitations.RemoteFailure.class,
        () -> service().cancel(73, Reason.REVOKED, this::invalidate));
    assertEquals("PENDING", state());
    pendingFixture();
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(75,'TENANT_ADMIN','INVITE','SUPERSEDED',?,?)",
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
    assertEquals(java.util.List.of(75L, 73L), service().pendingInviteIds(100));
    assertEquals(java.util.List.of(75L), service().pendingInviteIds(1));
    assertThrows(IllegalArgumentException.class, () -> service().pendingInviteIds(0));
    assertThrows(IllegalArgumentException.class, () -> service().pendingInviteIds(101));
    assertEquals(0, releases.get());
  }

  @Test
  void acknowledgedBoundCleanupRemainsDiscoverableWithoutReleasingFromItsOriginalReceipt()
      throws Exception {
    recoveryFixture();
    when(receiver.cancel(any()))
        .thenAnswer(
            call ->
                restrictions.verify(
                    call.getArgument(0),
                    terminals
                        .path("expiredBound")
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    true));
    service().cancel(73, Reason.REVOKED, this::invalidate);
    assertTrue(service().verifiedRestriction(73).cleanupRequired());
    assertEquals(java.util.List.of(73L), service().recoveryInviteIds(100));
    assertThrows(AccessDeniedException.class, () -> service().withAcknowledged(73, this::release));
    assertEquals(0, releases.get());
  }

  @Test
  void readyLostReplyAndExpiredPendingAcceptanceStayVisibleButCommittedAndLivePendingDoNot()
      throws Exception {
    recoveryFixture();
    activation(75, "READY", Instant.parse("2031-01-01T00:00:00Z"));
    activation(76, "WAITING_OTP", before.instant());
    activation(77, "WAITING_OTP", Instant.parse("2031-01-01T00:00:00Z"));
    activation(78, "COMMITTED", before.instant());
    assertEquals(java.util.List.of(75L, 76L), service().recoveryInviteIds(100));
    verifyNoInteractions(receiver);
    assertEquals(0, releases.get());
  }

  @Test
  void durableLastAttemptMovesOneUnknownBehindTheFollowingAccountAcrossWorkerRestart()
      throws Exception {
    recoveryFixture();
    activation(75, "READY", before.instant());
    activation(76, "READY", before.instant());
    assertEquals(java.util.List.of(75L), service().recoveryInviteIds(1));
    service().touchRecovery(75);
    assertEquals(java.util.List.of(76L, 75L), service().recoveryInviteIds(100));
    assertEquals(java.util.List.of(76L), service().recoveryInviteIds(1));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM protected_invitation_cleanup_status WHERE invite_id=75",
            Integer.class));
    assertThrows(IllegalArgumentException.class, () -> service().recoveryInviteIds(0));
    assertThrows(IllegalArgumentException.class, () -> service().recoveryInviteIds(101));
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx ->
                assertThrows(IllegalStateException.class, () -> service().recoveryInviteIds(100)));
    verifyNoInteractions(receiver);
  }

  @Test
  void expiredAcceptedActivationInvalidatesInsideTheSameCancellationIntentBeforeUnknownRpc()
      throws Exception {
    // This fixture uses real approval, acceptance and activation SQL owners; only native identity
    // receipt restoration and external RPC are explicit doubles, as in the activation owner suite.
    for (String column : new String[] {
        "accepted_by_user_id VARCHAR(36)", "accepted_at TIMESTAMP", "provisioning_status VARCHAR(32)",
        "provisioning_failure_reason VARCHAR(255)", "active_recipient_key VARCHAR(255)", "update_date TIMESTAMP"})
      jdbc.execute("ALTER TABLE account_invite ADD " + column);
    for (String migration : new String[] {
        "20261022_protected_invitation_acceptance_367", "20261022_protected_account_activation_367"})
      for (String sql : Files.readString(Path.of("src/main/resources/db/changelog/changeset", migration, "migrate.sql")).split(";"))
        if (!sql.isBlank()) jdbc.execute(sql);
    jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,username VARCHAR(255),first_name VARCHAR(255),last_name VARCHAR(255),email VARCHAR(255),type VARCHAR(10),managed_paused BOOLEAN)");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT,revision BIGINT,last_activity TIMESTAMP,due_at TIMESTAMP,status VARCHAR(20),attempts INT)");
    String token = "c".repeat(64);
    jdbc.update("UPDATE account_invite SET status='EMAIL_SENT',token_hash=?,provisioning_status='PENDING' WHERE id=73",token);
    byte[] key = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    var approvals = new ProtectedInvitationCodec(key,before,"synthetic",proposal.issuerRealm());
    var acceptance = new JdbcProtectedInvitationAcceptance(jdbc,manager,journal,approvals,json,before);
    var claim = acceptance.claim(73,token,json.read("{\"password\":\"synthetic-memory-only\"}",JsonNode.class));
    String account = "bf75ecf1-e612-414a-9e20-fd0858957f68";
    var original = mock(ProtectedAccountCodec.VerifiedCreated.class);
    when(original.accountId()).thenReturn(account);
    when(original.realmId()).thenReturn(proposal.issuerRealm());
    when(original.approvalId()).thenReturn(proposal.approvalId());
    when(original.claimId()).thenReturn(claim.claimId());
    when(original.attemptId()).thenReturn(claim.attemptId());
    when(original.executionId()).thenReturn(claim.executionId());
    when(original.inviteId()).thenReturn("73");
    when(original.registrationKind()).thenReturn("TENANT_ADMIN");
    when(original.tenantId()).thenReturn(7L);
    var accounts = mock(ProtectedAccountCodec.class);
    when(accounts.restore(any(),eq("synthetic-owned-checkpoint"))).thenReturn(original);
    var admin = java.util.Map.of("id",account,"tenantId",7L,"username","ada@example.test","firstName","Ada","lastName","Lovelace","email","ada@example.test","type","TENANT","managedPaused",false);
    var enrollment = java.util.Map.of("id",account,"tenantId",7L,"months",12,"revision",4L,"lastActivity",before.instant().toString(),"dueAt",before.instant().plusSeconds(365L*86400).toString(),"state","ACTIVE","attempts",0);
    jdbc.update("INSERT INTO admin VALUES(?,7,'ada@example.test','Ada','Lovelace','ada@example.test','TENANT',false)",account);
    jdbc.update("INSERT INTO account_inactivity VALUES(?,7,12,4,?,?,'ACTIVE',0)",account,Timestamp.from(before.instant()),Timestamp.from(before.instant().plusSeconds(365L*86400)));
    String effects = json.canonical(java.util.Map.of("admin",admin,"inactivity",enrollment));
    jdbc.update("INSERT INTO protected_account_activation(invite_id,claim_id,attempt_id,execution_id,account_id,state,created_checkpoint,local_effects_json,local_effects_digest,local_outbox_id,created_at,updated_at) VALUES(73,?,?,?,?,'WAITING_OTP','synthetic-owned-checkpoint',?,?,?,?,?)",
        claim.claimId().toString(),claim.attemptId().toString(),claim.executionId().toString(),account,effects,json.digest("protected-local-registration/v1",json.read(effects,JsonNode.class)),UUID.randomUUID().toString(),Timestamp.from(before.instant()),Timestamp.from(before.instant()));
    jdbc.update("UPDATE protected_invitation_acceptance SET account_id=? WHERE invite_id=73",account);
    jdbc.update("UPDATE account_invite SET status='ACCEPTED',accepted_by_user_id=?,provisioning_status='IN_PROGRESS',active_recipient_key='still-held' WHERE id=73",account);
    var historical = new JdbcProtectedInvitationAcceptance(jdbc,manager,journal,approvals,json,after).historical(73);
    var activation = new JdbcProtectedAccountActivation(jdbc,manager,journal,accounts,json,
        mock(ProtectedOtpBootstrap.class),mock(de.caritas.cob.userservice.api.port.out.AdminRepository.class),
        mock(de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.class),after);
    // Prove the actual owner callback and its data preconditions before testing cancellation order.
    new TransactionTemplate(manager).executeWithoutResult(tx -> {
      assertDoesNotThrow(() -> activation.restrictExpiredWithinOwner(historical));
      assertEquals("EXPIRED",status());
      tx.setRollbackOnly();
    });
    assertEquals("ACCEPTED",status());
    assertEquals(0,intents());
    when(receiver.cancel(any())).thenAnswer(call -> {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertEquals("EXPIRED",status());
      assertEquals("CANCEL_REQUESTED",authorization());
      assertEquals("PENDING",state());
      throw new KeycloakProtectedInvitations.RemoteFailure(0);
    });
    var callbacks = new AtomicInteger();
    assertThrows(KeycloakProtectedInvitations.RemoteFailure.class, () -> service().cancel(73,Reason.ISSUANCE_ABORTED,() -> {
      callbacks.incrementAndGet();activation.restrictExpiredWithinOwner(historical);
    }));
    assertEquals(1,callbacks.get());
    assertEquals("EXPIRED",status());
    assertNull(jdbc.queryForObject("SELECT token_hash FROM account_invite WHERE id=73",String.class));
    assertEquals("still-held",jdbc.queryForObject("SELECT active_recipient_key FROM account_invite WHERE id=73",String.class));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM admin",Integer.class));
    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM account_inactivity",Integer.class));
    assertEquals(0,releases.get());
    assertThrows(KeycloakProtectedInvitations.RemoteFailure.class, () -> service().cancel(73,Reason.ISSUANCE_ABORTED,() -> fail("Saved intent must not invalidate again")));
    assertEquals(1,callbacks.get());
  }

  private void recoveryFixture() throws Exception {
    pendingFixture();
    jdbc.execute("ALTER TABLE account_invite ADD provisioning_status VARCHAR(32) NULL");
    for (String migration :
        new String[] {
          "20261022_protected_invitation_acceptance_367",
          "20261022_protected_account_activation_367",
          "20261022_protected_invitation_cleanup_status_367"
        })
      for (String sql :
          Files.readString(
                  Path.of("src/main/resources/db/changelog/changeset", migration, "migrate.sql"))
              .split(";")) if (!sql.isBlank()) jdbc.execute(sql);
  }

  private void activation(long id, String state, Instant expiry) {
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,tenant_id,purpose,status,provisioning_status,expires_at,update_date) VALUES(?,'TENANT_ADMIN',7,'INVITE','ACCEPTED','IN_PROGRESS',?,?)",
        id,
        Timestamp.from(expiry),
        Timestamp.from(before.instant()));
    // Discovery facts only: these SQL rows mint no private claim, recovery or native authority.
    jdbc.update(
        "INSERT INTO protected_account_activation(invite_id,claim_id,attempt_id,execution_id,account_id,state,created_checkpoint,local_effects_json,local_effects_digest,local_outbox_id,created_at,updated_at) VALUES(?,?,?,?,?,?,'synthetic-not-authority','{}',?,?,?,?)",
        id,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        state,
        "a".repeat(64),
        UUID.randomUUID().toString(),
        Timestamp.from(before.instant()),
        Timestamp.from(before.instant()));
  }

  private void pendingFixture() {
    jdbc.execute("ALTER TABLE account_invite ADD update_date TIMESTAMP NULL");
    jdbc.execute("ALTER TABLE account_invite ADD superseded_by_invite_id BIGINT NULL");
    jdbc.update(
        "UPDATE account_invite SET update_date=? WHERE id=73", Timestamp.from(before.instant()));
  }

  private JdbcProtectedInvitationCancellation service() {
    return service(
        jdbc,
        id -> {
          throw new AssertionError("Existing exact proposal must be retained");
        });
  }

  private JdbcProtectedInvitationCancellation service(
      JdbcTemplate sql, Function<Long, FrozenApproval> missing) {
    return new JdbcProtectedInvitationCancellation(
        sql, manager, journal, restrictions, json, receiver, missing, after);
  }

  private ProtectedInvitationRestrictionCodec.VerifiedRestriction expired(
      FrozenCancellation frozen) {
    return restrictions.verify(
        frozen,
        terminals.path("expired").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
        true);
  }

  private void invalidate() {
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL WHERE id=73");
  }

  private int release() {
    return releases.incrementAndGet();
  }

  private String status() {
    return jdbc.queryForObject("SELECT status FROM account_invite WHERE id=73", String.class);
  }

  private String authorization() {
    return jdbc.queryForObject(
        "SELECT state FROM protected_invitation_authorization WHERE invite_id=73", String.class);
  }

  private String state() {
    return jdbc.queryForObject(
        "SELECT state FROM protected_invitation_cancellation WHERE invite_id=73", String.class);
  }

  private int intents() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM protected_invitation_cancellation", Integer.class);
  }
}
