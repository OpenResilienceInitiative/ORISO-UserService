package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCommitted;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.Completion;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.PreparedBootstrap;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient.VerifiedTenant;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real owner SQL/rollback with explicit native, OTP and repository seam doubles. */
class JdbcProtectedAccountActivationTest {
  private static final String ACCOUNT = "bf75ecf1-e612-414a-9e20-fd0858957f68";
  private static final String TOKEN = "c".repeat(64);
  private static final String CHECKPOINT = "synthetic-authenticated-checkpoint";
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
  private final Policy policy = new Policy(12, 4, clock.instant());
  private final ProtectedAccountCodec accounts = mock(ProtectedAccountCodec.class);
  private final ProtectedOtpBootstrap otp = mock(ProtectedOtpBootstrap.class);
  private final AdminRepository admins = mock(AdminRepository.class);
  private final AccountInactivityEnrollmentService inactivity =
      mock(AccountInactivityEnrollmentService.class);
  private final PendingOwnedAccount pending = mock(PendingOwnedAccount.class);
  private final PreparedBootstrap prepared = mock(PreparedBootstrap.class);
  private final IdentityOtpCredential setup =
      new IdentityOtpCredential(false, "synthetic-setup-only", null, IdentityOtpType.APP);
  private VerifiedCreated created;
  private AcceptanceClaim claim;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager manager;
  private JdbcProtectedInvitationJournal journal;
  private AccountLifecycleCodec json;

  @BeforeEach
  void ownerDatabase() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    jdbc.execute(
        "CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),first_name VARCHAR(255),last_name VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36),accepted_by_user_id VARCHAR(36),accepted_at TIMESTAMP,provisioning_status VARCHAR(32),provisioning_failure_reason VARCHAR(255),active_recipient_key VARCHAR(255),update_date TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE admin(admin_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,username VARCHAR(255),first_name VARCHAR(255),last_name VARCHAR(255),email VARCHAR(255),type VARCHAR(10),managed_paused BOOLEAN,create_date TIMESTAMP,update_date TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT,revision BIGINT,last_activity TIMESTAMP,due_at TIMESTAMP,status VARCHAR(20),attempts INT DEFAULT 0)");
    jdbc.execute(
        "CREATE TABLE synthetic_otp_stage(invite_id BIGINT PRIMARY KEY,ciphertext VARCHAR(255))");
    for (String name :
        new String[] {
          "20261022_protected_invitation_367",
          "20261022_protected_invitation_acceptance_367",
          "20261022_protected_account_activation_367",
          "20261022_protected_account_cleanup_367"
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
    var approvals = new ProtectedInvitationCodec(key, clock, "oriso", "native-synthetic-realm");
    JsonNode vector =
        json.read(
            Files.readString(Path.of("src/test/resources/scenario-c/protected-approval-v1.json")),
            JsonNode.class);
    var frozen =
        approvals.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            json.canonical(vector.path("request")),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    jdbc.update(
        "INSERT INTO account_invite(id,target_role,tenant_id,recipient_email,first_name,last_name,purpose,status,expires_at,token_hash,provisioning_status) VALUES(73,'TENANT_ADMIN',7,'ada@example.test','Ada','Lovelace','INVITE','EMAIL_SENT',?,?,'PENDING')",
        Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")),
        TOKEN);
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, json, clock);
    journal.propose(73, frozen);
    journal.recordReady(
        frozen.approvalId(),
        approvals.verifyAuthorized(frozen, json.canonical(vector.path("response"))));
    var acceptance =
        new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, json, clock);
    claim =
        acceptance.claim(
            73, TOKEN, json.read("{\"password\":\"synthetic-memory-only\"}", JsonNode.class));
    created =
        mock(
            VerifiedCreated.class,
            invocation ->
                switch (invocation.getMethod().getName()) {
                  case "email" -> "ada@example.test";
                  case "firstName" -> "Ada";
                  case "lastName" -> "Lovelace";
                  default -> RETURNS_DEFAULTS.answer(invocation);
                });
    when(created.accountId()).thenReturn(ACCOUNT);
    when(created.realmId()).thenReturn(frozen.issuerRealm());
    when(created.claimId()).thenReturn(claim.claimId());
    when(created.attemptId()).thenReturn(claim.attemptId());
    when(created.executionId()).thenReturn(claim.executionId());
    when(created.approvalId()).thenReturn(frozen.approvalId());
    when(created.tenantId()).thenReturn(7L);
    when(created.registrationKind()).thenReturn("TENANT_ADMIN");
    when(created.inviteId()).thenReturn("73");
    when(pending.claim()).thenReturn(claim);
    when(pending.created()).thenReturn(created);
    when(accounts.checkpoint(created)).thenReturn(CHECKPOINT);
    when(accounts.restore(any(), eq(CHECKPOINT))).thenReturn(created);
    when(admins.saveAndFlush(any(Admin.class)))
        .thenAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              Admin row = call.getArgument(0);
              jdbc.update(
                  "INSERT INTO admin(admin_id,tenant_id,username,first_name,last_name,email,type,managed_paused) VALUES(?,?,?,?,?,?,?,false)",
                  row.getId(),
                  row.getTenantId(),
                  row.getUsername(),
                  row.getFirstName(),
                  row.getLastName(),
                  row.getEmail(),
                  row.getType().name());
              return row;
            });
    doAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              jdbc.update(
                  "INSERT INTO account_inactivity VALUES(?,7,12,4,?,?,'ACTIVE',0)",
                  ACCOUNT,
                  Timestamp.from(clock.instant()),
                  Timestamp.from(clock.instant().plusSeconds(365L * 86400)));
              return null;
            })
        .when(inactivity)
        .enroll(eq(ACCOUNT), eq(7L), eq(policy));
    when(otp.stagePrepared(any(), eq(prepared)))
        .thenAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              jdbc.update("INSERT INTO synthetic_otp_stage VALUES(73,'synthetic-ciphertext')");
              return setup;
            });
    when(otp.currentSetup(any()))
        .thenAnswer(
            call -> {
              assertFalse(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "The actual OTP currentSetup port requires completed owner SQL");
              return setup;
            });
    when(otp.currentSetupWithinOwner(any()))
        .thenAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return setup;
            });
  }

  @Test
  void registrationCommitsAdminEnrollmentAndOtpStageTogetherWithoutNativeActivation() {
    assertEquals(
        JdbcProtectedAccountActivation.Phase.UNSTAGED,
        assertDoesNotThrow(() -> restart().phase(claim)));
    assertEquals(
        setup, assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy)));
    assertEquals(1, count("admin"));
    assertEquals(1, count("account_inactivity"));
    assertEquals(1, count("synthetic_otp_stage"));
    assertEquals("WAITING_OTP", state());
    assertEquals(JdbcProtectedAccountActivation.Phase.WAITING_OTP, restart().phase(claim));
    assertEquals(
        "ACCEPTED", jdbc.queryForObject("SELECT status FROM account_invite", String.class));
    assertEquals(
        ACCOUNT,
        jdbc.queryForObject(
            "SELECT account_id FROM protected_invitation_acceptance", String.class));
    assertEquals(setup, restart().currentSetup(claim));
    assertSame(created, restart().created(claim));
    assertFalse(
        jdbc.queryForList("SELECT * FROM protected_account_activation")
            .toString()
            .contains(setup.secret()));
  }

  @Test
  void otpStageFailureRollsBackAllLocalEffectsAndLeavesInvitationRetryable() {
    when(otp.stagePrepared(any(), eq(prepared)))
        .thenThrow(new IllegalStateException("synthetic stage failure"));
    assertThrows(RuntimeException.class, () -> restart().stage(claim, pending, prepared, policy));
    assertEquals(0, count("admin"));
    assertEquals(0, count("account_inactivity"));
    assertEquals(0, count("protected_account_activation"));
    assertEquals(
        "EMAIL_SENT", jdbc.queryForObject("SELECT status FROM account_invite", String.class));
    verify(admins).saveAndFlush(any());
  }

  @Test
  void readinessRequiresCommittedOtpAndAnUnchangedActualLocalAdmin() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    Completion complete = completion();
    assertThrows(RuntimeException.class, () -> restart().ready(claim, complete));
    assertEquals("WAITING_OTP", state());
    allowCompletion(complete);
    jdbc.update("UPDATE admin SET tenant_id=8 WHERE admin_id=?", ACCOUNT);
    assertThrows(RuntimeException.class, () -> restart().ready(claim, complete));
    assertEquals("WAITING_OTP", state());
    jdbc.update("UPDATE admin SET tenant_id=7 WHERE admin_id=?", ACCOUNT);
    var ready = assertDoesNotThrow(() -> restart().ready(claim, complete));
    assertEquals("READY", state());
    assertEquals(1, ready.outcome().journalVersion());
    assertSame(created, ready.created());
    assertEquals(ready.outcome(), restart().ready(claim, complete).outcome());
  }

  @Test
  void onlyTheSameVerifiedNativeCommitFinishesTheOwningInvitation() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    Completion complete = completion();
    allowCompletion(complete);
    var ready = assertDoesNotThrow(() -> restart().ready(claim, complete));
    VerifiedCommitted nativeCommit = mock(VerifiedCommitted.class);
    when(nativeCommit.claimId()).thenReturn(UUID.randomUUID());
    assertThrows(RuntimeException.class, () -> restart().acknowledge(ready, nativeCommit));
    assertEquals("READY", state());
    when(nativeCommit.claimId()).thenReturn(claim.claimId());
    when(nativeCommit.attemptId()).thenReturn(claim.attemptId());
    when(nativeCommit.accountId()).thenReturn(ACCOUNT);
    when(nativeCommit.terminalReceipt()).thenReturn("synthetic-authenticated-terminal-receipt");
    when(nativeCommit.localOutcome())
        .thenReturn(new AccountLifecycleDtos.LocalOutcome(1, UUID.randomUUID(), "f".repeat(64)));
    assertThrows(RuntimeException.class, () -> restart().acknowledge(ready, nativeCommit));
    assertEquals("READY", state());
    when(nativeCommit.localOutcome()).thenReturn(ready.outcome());
    assertDoesNotThrow(() -> restart().acknowledge(ready, nativeCommit));
    assertEquals("COMMITTED", state());
    assertEquals(
        "COMPLETED",
        jdbc.queryForObject("SELECT provisioning_status FROM account_invite", String.class));
    assertDoesNotThrow(() -> restart().acknowledge(ready, nativeCommit));
    verify(admins, times(1)).saveAndFlush(any());
  }

  @Test
  void aLostCommitReplyCanFinishFromConsumedStatusAfterExpiryWithoutNewActivation() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    Completion complete = completion();
    allowCompletion(complete);
    var ready = assertDoesNotThrow(() -> restart().ready(claim, complete));
    Clock late = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
    var historical = assertDoesNotThrow(() -> acceptanceAt(late).historical(73));
    assertThrows(RuntimeException.class, () -> acceptanceAt(late).resume(73, TOKEN));
    assertThrows(RuntimeException.class, () -> activationAt(late).ready(claim, complete));
    // A native commit can permit the first login before its lost reply is reconciled.
    jdbc.update(
        "UPDATE account_inactivity SET last_activity=? WHERE identity_id=?",
        Timestamp.from(late.instant()),
        ACCOUNT);
    clearInvocations(otp);
    var saved = assertDoesNotThrow(() -> activationAt(late).historicalReady(historical));
    assertEquals(ready.outcome(), saved.outcome());
    assertSame(created, saved.created());
    var terminal = committed(ready.outcome());
    assertDoesNotThrow(() -> activationAt(late).acknowledge(saved, terminal));
    assertDoesNotThrow(() -> activationAt(late).acknowledge(saved, terminal));
    assertEquals("COMMITTED", state());
    assertEquals(
        "COMPLETED",
        jdbc.queryForObject("SELECT provisioning_status FROM account_invite", String.class));
    verifyNoInteractions(otp);
    verify(admins, times(1)).saveAndFlush(any());
    assertNotEquals(JdbcProtectedAccountActivation.ReadyActivation.class, saved.getClass());
  }

  @Test
  void historicalStatusCannotAcknowledgeAForeignOutcomeOrAlteredSavedReadyDigest() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    Completion complete = completion();
    allowCompletion(complete);
    var ready = assertDoesNotThrow(() -> restart().ready(claim, complete));
    Clock late = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
    jdbc.update("UPDATE account_invite SET status='REVOKED',token_hash=NULL");
    jdbc.update("UPDATE protected_invitation_authorization SET state='CANCEL_REQUESTED'");
    var historical = assertDoesNotThrow(() -> acceptanceAt(late).historical(73));
    var saved = assertDoesNotThrow(() -> activationAt(late).historicalReady(historical));
    var foreign =
        committed(new AccountLifecycleDtos.LocalOutcome(1, UUID.randomUUID(), "f".repeat(64)));
    assertThrows(RuntimeException.class, () -> activationAt(late).acknowledge(saved, foreign));
    assertEquals("READY", state());
    jdbc.update("UPDATE protected_account_activation SET ready_effects_digest=?", "a".repeat(64));
    assertThrows(RuntimeException.class, () -> activationAt(late).historicalReady(historical));
    assertThrows(
        RuntimeException.class,
        () -> activationAt(late).acknowledge(saved, committed(ready.outcome())));
    assertEquals("READY", state());
    assertEquals(
        "IN_PROGRESS",
        jdbc.queryForObject("SELECT provisioning_status FROM account_invite", String.class));
  }

  @Test
  void historicalCustodyCannotTurnAnOtpPendingOrMismatchedInvitationIntoReady() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    Clock late = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
    var historical = assertDoesNotThrow(() -> acceptanceAt(late).historical(73));
    assertThrows(RuntimeException.class, () -> activationAt(late).historicalReady(historical));
    assertEquals("WAITING_OTP", state());
    jdbc.update("UPDATE account_invite SET accepted_by_user_id=?", UUID.randomUUID().toString());
    assertThrows(RuntimeException.class, () -> acceptanceAt(late).historical(73));
    assertEquals("WAITING_OTP", state());
    verify(otp, never()).setupApp(any(), any());
    verify(otp, never()).startEmail(any());
    verify(otp, never()).finishEmail(any(), any());
  }

  @Test
  void reservedAdminStagingRequiresTheExactPrivateTenantResultAndFreezesItsReceipt()
      throws Exception {
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    var approvals = new ProtectedInvitationCodec(key, clock, "oriso", "native-synthetic-realm");
    JsonNode vector =
        json.read(
            Files.readString(
                Path.of("src/test/resources/scenario-c/protected-approval-reserved-v1.json")),
            JsonNode.class);
    jdbc.update("DELETE FROM protected_invitation_acceptance");
    jdbc.update("DELETE FROM protected_invitation_authorization");
    jdbc.update(
        "UPDATE account_invite SET tenant_id_reservation_token=?,agency_id=?",
        vector.path("syntheticReservationToken").asText(),
        vector.path("request").path("scope").path("agencyBinding").path("agencyId").longValue());
    var frozen =
        approvals.freeze(
            UUID.fromString(vector.path("approvalId").asText()),
            json.canonical(vector.path("request")),
            vector.path("issuerRealm").asText(),
            vector.path("issuerSubject").asText());
    journal.propose(73, frozen);
    journal.recordReady(
        frozen.approvalId(),
        approvals.verifyAuthorized(frozen, json.canonical(vector.path("response"))));
    claim =
        new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, json, clock)
            .claim(
                73, TOKEN, json.read("{\"password\":\"synthetic-memory-only\"}", JsonNode.class));
    when(pending.claim()).thenReturn(claim);
    when(created.claimId()).thenReturn(claim.claimId());
    when(created.attemptId()).thenReturn(claim.attemptId());
    when(created.executionId()).thenReturn(claim.executionId());
    when(created.approvalId()).thenReturn(frozen.approvalId());
    assertThrows(RuntimeException.class, () -> restart().stage(claim, pending, prepared, policy));
    assertEquals(0, count("admin"));
    var tenant = mock(VerifiedTenant.class);
    when(tenant.tenantId()).thenReturn(8L);
    assertThrows(
        RuntimeException.class,
        () -> restart().stageReserved(claim, pending, prepared, policy, tenant));
    when(tenant.tenantId()).thenReturn(7L);
    when(tenant.claimId()).thenReturn(claim.claimId());
    when(tenant.attemptId()).thenReturn(UUID.randomUUID());
    when(tenant.executionId()).thenReturn(claim.executionId());
    when(tenant.receipt()).thenReturn("synthetic-authenticated-tenant-receipt");
    assertThrows(
        RuntimeException.class,
        () -> restart().stageReserved(claim, pending, prepared, policy, tenant));
    assertEquals(0, count("admin"));
    when(tenant.attemptId()).thenReturn(claim.attemptId());
    assertEquals(
        setup,
        assertDoesNotThrow(
            () -> restart().stageReserved(claim, pending, prepared, policy, tenant)));
    assertEquals(setup, restart().stageReserved(claim, pending, prepared, policy, tenant));
    assertEquals(1, count("admin"));
    assertEquals("WAITING_OTP", state());
    var effects =
        json.read(
            jdbc.queryForObject(
                "SELECT local_effects_json FROM protected_account_activation", String.class),
            JsonNode.class);
    assertEquals(tenant.receipt(), effects.path("tenantBootstrapReceipt").asText());
    when(tenant.receipt()).thenReturn("synthetic-different-tenant-receipt");
    assertThrows(
        RuntimeException.class,
        () -> restart().stageReserved(claim, pending, prepared, policy, tenant));
    assertEquals(1, count("admin"));
  }

  @Test
  void verifiedRecoveryCleansOnlyTheOwnedLocalRowsAtomicallyAndReplaysItsStableOutcome() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    jdbc.update("UPDATE account_invite SET active_recipient_key='still-owned'");
    var recovered = recovery();
    allowDiscard(claim.executionId());
    var cleaned = assertDoesNotThrow(() -> restart().discard(claim, recovered));
    assertEquals(0, count("admin"));
    assertEquals(0, count("account_inactivity"));
    assertEquals(0, count("synthetic_otp_stage"));
    assertEquals("LOCAL_CLEANED", state());
    assertEquals(JdbcProtectedAccountActivation.Phase.LOCAL_CLEANED, restart().phase(claim));
    assertEquals(cleaned, restart().discard(claim, recovered));
    assertEquals(1, cleaned.journalVersion());
    assertEquals(
        "still-owned",
        jdbc.queryForObject("SELECT active_recipient_key FROM account_invite", String.class));
    assertEquals(
        ACCOUNT,
        jdbc.queryForObject(
            "SELECT account_id FROM protected_invitation_acceptance", String.class));
    verify(otp, times(1)).discardWithinOwner(any());
    var foreign = recovery();
    assertThrows(RuntimeException.class, () -> restart().discard(claim, foreign));
  }

  @Test
  void encryptedOtpCleanupFailureRollsBackAdminEnrollmentAndCleanupJournal() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    doAnswer(
            call -> {
              jdbc.update("DELETE FROM synthetic_otp_stage");
              throw new IllegalStateException("synthetic encrypted-row failure");
            })
        .when(otp)
        .discardWithinOwner(any());
    assertThrows(RuntimeException.class, () -> restart().discard(claim, recovery()));
    assertEquals(1, count("admin"));
    assertEquals(1, count("account_inactivity"));
    assertEquals(1, count("synthetic_otp_stage"));
    assertEquals("WAITING_OTP", state());
    verify(otp).discardWithinOwner(any());
  }

  @Test
  void recoveryRefusesDriftForeignProofAndCommittedAccountsBeforeAnyDeletion() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    var recovered = recovery();
    jdbc.update("UPDATE admin SET tenant_id=8");
    assertThrows(RuntimeException.class, () -> restart().discard(claim, recovered));
    jdbc.update("UPDATE admin SET tenant_id=7");
    when(recovered.creationProof()).thenReturn("foreign-creation-proof");
    assertThrows(RuntimeException.class, () -> restart().discard(claim, recovered));
    when(recovered.creationProof()).thenReturn("synthetic-authenticated-creation-proof");
    when(recovered.generation()).thenReturn(0L);
    assertThrows(RuntimeException.class, () -> restart().discard(claim, recovered));
    when(recovered.generation()).thenReturn(1L);
    when(recovered.state()).thenReturn("COMMITTED");
    assertThrows(RuntimeException.class, () -> restart().discard(claim, recovered));
    when(recovered.state()).thenReturn("RECOVERY_CLAIMED");
    var complete = completion();
    allowCompletion(complete);
    var ready = restart().ready(claim, complete);
    restart().acknowledge(ready, committed(ready.outcome()));
    assertThrows(RuntimeException.class, () -> restart().discard(claim, recovered));
    assertEquals(1, count("admin"));
    assertEquals("COMMITTED", state());
    verify(otp, never()).discardWithinOwner(any());
  }

  @Test
  void reducingCleanupAfterExpiryAndRevocationUsesSavedIncarnationWithoutLiveCredential() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    var complete = completion();
    allowCompletion(complete);
    restart().ready(claim, complete);
    jdbc.update(
        "UPDATE account_invite SET status='REVOKED',token_hash=NULL,active_recipient_key='still-owned'");
    jdbc.update("UPDATE protected_invitation_authorization SET state='CANCEL_REQUESTED'");
    Clock late = Clock.fixed(Instant.parse("2030-01-03T00:00:00Z"), ZoneOffset.UTC);
    var historical = acceptanceAt(late).historical(73);
    allowDiscard(complete.bootstrapIncarnation());
    var recovered = recovery();
    var cleaned = assertDoesNotThrow(() -> activationAt(late).discard(historical, recovered));
    assertEquals(cleaned, activationAt(late).discard(historical, recovered));
    assertEquals("LOCAL_CLEANED", state());
    assertEquals(0, count("admin"));
    assertEquals("REVOKED", jdbc.queryForObject("SELECT status FROM account_invite", String.class));
    assertEquals(
        "still-owned",
        jdbc.queryForObject("SELECT active_recipient_key FROM account_invite", String.class));
  }

  private VerifiedRecovery recovery() {
    when(created.creationProof()).thenReturn("synthetic-authenticated-creation-proof");
    var recovered = mock(VerifiedRecovery.class);
    when(recovered.approvalId()).thenReturn(claim.frozen().approvalId());
    when(recovered.attemptId()).thenReturn(claim.attemptId());
    when(recovered.executionId()).thenReturn(UUID.randomUUID());
    when(recovered.accountId()).thenReturn(ACCOUNT);
    when(recovered.generation()).thenReturn(1L);
    when(recovered.state()).thenReturn("RECOVERY_CLAIMED");
    when(recovered.creationProof()).thenReturn("synthetic-authenticated-creation-proof");
    return recovered;
  }

  private void allowDiscard(UUID incarnation) {
    doAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              JdbcProtectedAccountActivation.VerifiedDiscard exact = call.getArgument(0);
              assertEquals(73, exact.inviteId());
              assertEquals(claim.claimId(), exact.claimId());
              assertEquals(claim.attemptId(), exact.attemptId());
              assertEquals(claim.executionId(), exact.executionId());
              assertEquals(ACCOUNT, exact.accountId());
              assertEquals(incarnation, exact.bootstrapIncarnation());
              assertEquals(1, jdbc.update("DELETE FROM synthetic_otp_stage WHERE invite_id=73"));
              return null;
            })
        .when(otp)
        .discardWithinOwner(any());
  }

  private VerifiedCommitted committed(AccountLifecycleDtos.LocalOutcome local) {
    var terminal = mock(VerifiedCommitted.class);
    when(terminal.claimId()).thenReturn(claim.claimId());
    when(terminal.attemptId()).thenReturn(claim.attemptId());
    when(terminal.accountId()).thenReturn(ACCOUNT);
    when(terminal.localOutcome()).thenReturn(local);
    when(terminal.terminalReceipt()).thenReturn("synthetic-authenticated-terminal-receipt");
    return terminal;
  }

  private JdbcProtectedInvitationJournal journalAt(Clock at) {
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    var approvals = new ProtectedInvitationCodec(key, at, "oriso", "native-synthetic-realm");
    return new JdbcProtectedInvitationJournal(jdbc, manager, approvals, json, at);
  }

  private JdbcProtectedInvitationAcceptance acceptanceAt(Clock at) {
    byte[] key =
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    var approvals = new ProtectedInvitationCodec(key, at, "oriso", "native-synthetic-realm");
    return new JdbcProtectedInvitationAcceptance(jdbc, manager, journalAt(at), approvals, json, at);
  }

  private JdbcProtectedAccountActivation activationAt(Clock at) {
    return new JdbcProtectedAccountActivation(
        jdbc, manager, journalAt(at), accounts, json, otp, admins, inactivity, at);
  }

  private Completion completion() {
    var complete = mock(Completion.class);
    when(complete.claimId()).thenReturn(claim.claimId());
    when(complete.attemptId()).thenReturn(claim.attemptId());
    when(complete.executionId()).thenReturn(claim.executionId());
    when(complete.accountId()).thenReturn(ACCOUNT);
    when(complete.bootstrapIncarnation())
        .thenReturn(UUID.fromString("475493a1-188f-49c8-84c2-d4aaed2e7092"));
    when(complete.receipt()).thenReturn("synthetic-verified-completion-receipt");
    return complete;
  }

  private void allowCompletion(Completion completion) {
    when(otp.completed(any()))
        .thenAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return completion;
            });
    when(otp.completedWithinOwner(any()))
        .thenAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return completion;
            });
  }

  private JdbcProtectedAccountActivation restart() {
    return new JdbcProtectedAccountActivation(
        jdbc, manager, journal, accounts, json, otp, admins, inactivity, clock);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private String state() {
    return jdbc.queryForObject(
        "SELECT state FROM protected_account_activation WHERE invite_id=73", String.class);
  }
}
