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
    when(receiver.cancel(any()))
        .thenAnswer(
            call -> {
              FrozenCancellation f = call.getArgument(0);
              assertEquals(first.get(), f);
              return expired(f);
            });
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
    jdbc.update("INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(74,'COUNSELLOR','INVITE','EXPIRED',?,?)",Timestamp.from(before.instant()),Timestamp.from(before.instant()));
    jdbc.update("INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(75,'PLATFORM_ADMIN','INVITE','EXPIRED',?,?)",Timestamp.from(before.instant()),Timestamp.from(before.instant()));
    jdbc.update("INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(76,'TENANT_ADMIN','INVITE','ACCEPTED',?,?)",Timestamp.from(before.instant()),Timestamp.from(before.instant()));
    assertEquals(java.util.List.of(75L),service().pendingInviteIds(100));
    assertEquals(0,releases.get());
    verify(receiver).cancel(any());
  }

  @Test
  void unknownRestrictionsMoveBehindOlderMissingIntentsAndTheRecoveryPageIsBounded() {
    when(receiver.cancel(any())).thenThrow(new KeycloakProtectedInvitations.RemoteFailure(0));
    assertThrows(KeycloakProtectedInvitations.RemoteFailure.class,()->service().cancel(73,Reason.REVOKED,this::invalidate));
    assertEquals("PENDING",state());
    pendingFixture();
    jdbc.update("INSERT INTO account_invite(id,target_role,purpose,status,expires_at,update_date) VALUES(75,'TENANT_ADMIN','INVITE','SUPERSEDED',?,?)",Timestamp.from(before.instant()),Timestamp.from(before.instant()));
    assertEquals(java.util.List.of(75L,73L),service().pendingInviteIds(100));
    assertEquals(java.util.List.of(75L),service().pendingInviteIds(1));
    assertThrows(IllegalArgumentException.class,()->service().pendingInviteIds(0));
    assertThrows(IllegalArgumentException.class,()->service().pendingInviteIds(101));
    assertEquals(0,releases.get());
  }

  private void pendingFixture(){
    jdbc.execute("ALTER TABLE account_invite ADD update_date TIMESTAMP NULL");
    jdbc.update("UPDATE account_invite SET update_date=? WHERE id=73",Timestamp.from(before.instant()));
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
