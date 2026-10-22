package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCommitted;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.Completion;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.PreparedBootstrap;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
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
  private final AccountInactivityEnrollmentService inactivity = mock(AccountInactivityEnrollmentService.class);
  private final PendingOwnedAccount pending = mock(PendingOwnedAccount.class);
  private final PreparedBootstrap prepared = mock(PreparedBootstrap.class);
  private final IdentityOtpCredential setup = new IdentityOtpCredential(false, "synthetic-setup-only", null, IdentityOtpType.APP);
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
    jdbc.execute("CREATE TABLE account_invite(id BIGINT PRIMARY KEY,target_role VARCHAR(64),tenant_id BIGINT,recipient_email VARCHAR(255),first_name VARCHAR(255),last_name VARCHAR(255),purpose VARCHAR(32),status VARCHAR(32),expires_at TIMESTAMP,token_hash VARCHAR(64),agency_id BIGINT,tenant_id_reservation_token VARCHAR(36),accepted_by_user_id VARCHAR(36),accepted_at TIMESTAMP,provisioning_status VARCHAR(32),provisioning_failure_reason VARCHAR(255),active_recipient_key VARCHAR(255),update_date TIMESTAMP)");
    jdbc.execute("CREATE TABLE admin(admin_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,username VARCHAR(255),first_name VARCHAR(255),last_name VARCHAR(255),email VARCHAR(255),type VARCHAR(10),managed_paused BOOLEAN,create_date TIMESTAMP,update_date TIMESTAMP)");
    jdbc.execute("CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,assigned_months INT,revision BIGINT,last_activity TIMESTAMP,due_at TIMESTAMP,status VARCHAR(20),attempts INT DEFAULT 0)");
    jdbc.execute("CREATE TABLE synthetic_otp_stage(invite_id BIGINT PRIMARY KEY,ciphertext VARCHAR(255))");
    for (String name : new String[] {"20261022_protected_invitation_367", "20261022_protected_invitation_acceptance_367", "20261022_protected_account_activation_367"}) {
      for (String sql : Files.readString(Path.of("src/main/resources/db/changelog/changeset/" + name + "/migrate.sql")).split(";")) {
        if (!sql.isBlank()) jdbc.execute(sql);
      }
    }
    byte[] key = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    json = new AccountLifecycleCodec(key);
    var approvals = new ProtectedInvitationCodec(key, clock, "oriso", "native-synthetic-realm");
    JsonNode vector = json.read(Files.readString(Path.of("src/test/resources/scenario-c/protected-approval-v1.json")), JsonNode.class);
    var frozen = approvals.freeze(UUID.fromString(vector.path("approvalId").asText()), json.canonical(vector.path("request")), vector.path("issuerRealm").asText(), vector.path("issuerSubject").asText());
    jdbc.update("INSERT INTO account_invite(id,target_role,tenant_id,recipient_email,first_name,last_name,purpose,status,expires_at,token_hash,provisioning_status) VALUES(73,'TENANT_ADMIN',7,'ada@example.test','Ada','Lovelace','INVITE','EMAIL_SENT',?,?,'PENDING')", Timestamp.from(Instant.parse("2030-01-02T03:04:05Z")), TOKEN);
    journal = new JdbcProtectedInvitationJournal(jdbc, manager, approvals, json, clock);
    journal.propose(73, frozen);
    journal.recordReady(frozen.approvalId(), approvals.verifyAuthorized(frozen, json.canonical(vector.path("response"))));
    var acceptance = new JdbcProtectedInvitationAcceptance(jdbc, manager, journal, approvals, json, clock);
    claim = acceptance.claim(73, TOKEN, json.read("{\"password\":\"synthetic-memory-only\"}", JsonNode.class));
    created = mock(VerifiedCreated.class, invocation -> switch (invocation.getMethod().getName()) {
      case "email" -> "ada@example.test";
      case "firstName" -> "Ada";
      case "lastName" -> "Lovelace";
      default -> RETURNS_DEFAULTS.answer(invocation);
    });
    when(created.accountId()).thenReturn(ACCOUNT);
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
    when(admins.saveAndFlush(any(Admin.class))).thenAnswer(call -> {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      Admin row = call.getArgument(0);
      jdbc.update("INSERT INTO admin(admin_id,tenant_id,username,first_name,last_name,email,type,managed_paused) VALUES(?,?,?,?,?,?,?,false)", row.getId(), row.getTenantId(), row.getUsername(), row.getFirstName(), row.getLastName(), row.getEmail(), row.getType().name());
      return row;
    });
    doAnswer(call -> {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      jdbc.update("INSERT INTO account_inactivity VALUES(?,7,12,4,?,?,'ACTIVE',0)", ACCOUNT, Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plusSeconds(365L * 86400)));
      return null;
    }).when(inactivity).enroll(eq(ACCOUNT), eq(7L), eq(policy));
    when(otp.stagePrepared(any(), eq(prepared))).thenAnswer(call -> {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      jdbc.update("INSERT INTO synthetic_otp_stage VALUES(73,'synthetic-ciphertext')");
      return setup;
    });
    when(otp.currentSetup(any())).thenReturn(setup);
  }

  @Test
  void registrationCommitsAdminEnrollmentAndOtpStageTogetherWithoutNativeActivation() {
    assertEquals(setup, assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy)));
    assertEquals(1, count("admin"));
    assertEquals(1, count("account_inactivity"));
    assertEquals(1, count("synthetic_otp_stage"));
    assertEquals("WAITING_OTP", state());
    assertEquals("ACCEPTED", jdbc.queryForObject("SELECT status FROM account_invite", String.class));
    assertEquals(ACCOUNT, jdbc.queryForObject("SELECT account_id FROM protected_invitation_acceptance", String.class));
    assertEquals(setup, restart().currentSetup(claim));
    assertSame(created, restart().created(claim));
    assertFalse(jdbc.queryForList("SELECT * FROM protected_account_activation").toString().contains(setup.secret()));
  }

  @Test
  void otpStageFailureRollsBackAllLocalEffectsAndLeavesInvitationRetryable() {
    when(otp.stagePrepared(any(), eq(prepared))).thenThrow(new IllegalStateException("synthetic stage failure"));
    assertThrows(RuntimeException.class, () -> restart().stage(claim, pending, prepared, policy));
    assertEquals(0, count("admin"));
    assertEquals(0, count("account_inactivity"));
    assertEquals(0, count("protected_account_activation"));
    assertEquals("EMAIL_SENT", jdbc.queryForObject("SELECT status FROM account_invite", String.class));
    verify(admins).saveAndFlush(any());
  }

  @Test
  void readinessRequiresCommittedOtpAndAnUnchangedActualLocalAdmin() {
    assertDoesNotThrow(() -> restart().stage(claim, pending, prepared, policy));
    Completion complete = completion();
    assertThrows(RuntimeException.class, () -> restart().ready(claim, complete));
    assertEquals("WAITING_OTP", state());
    when(otp.completed(any())).thenReturn(complete);
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
    when(otp.completed(any())).thenReturn(complete);
    var ready = assertDoesNotThrow(() -> restart().ready(claim, complete));
    VerifiedCommitted nativeCommit = mock(VerifiedCommitted.class);
    when(nativeCommit.claimId()).thenReturn(UUID.randomUUID());
    assertThrows(RuntimeException.class, () -> restart().acknowledge(ready, nativeCommit));
    assertEquals("READY", state());
    when(nativeCommit.claimId()).thenReturn(claim.claimId());
    when(nativeCommit.attemptId()).thenReturn(claim.attemptId());
    when(nativeCommit.accountId()).thenReturn(ACCOUNT);
    when(nativeCommit.terminalReceipt()).thenReturn("synthetic-authenticated-terminal-receipt");
    assertDoesNotThrow(() -> restart().acknowledge(ready, nativeCommit));
    assertEquals("COMMITTED", state());
    assertEquals("COMPLETED", jdbc.queryForObject("SELECT provisioning_status FROM account_invite", String.class));
    assertDoesNotThrow(() -> restart().acknowledge(ready, nativeCommit));
    verify(admins, times(1)).saveAndFlush(any());
  }

  private Completion completion() {
    var complete = mock(Completion.class);
    when(complete.claimId()).thenReturn(claim.claimId());
    when(complete.attemptId()).thenReturn(claim.attemptId());
    when(complete.executionId()).thenReturn(claim.executionId());
    when(complete.accountId()).thenReturn(ACCOUNT);
    when(complete.bootstrapIncarnation()).thenReturn(UUID.fromString("475493a1-188f-49c8-84c2-d4aaed2e7092"));
    when(complete.receipt()).thenReturn("synthetic-verified-completion-receipt");
    return complete;
  }

  private JdbcProtectedAccountActivation restart() {
    return new JdbcProtectedAccountActivation(jdbc, manager, journal, accounts, json, otp, admins, inactivity, clock);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private String state() {
    return jdbc.queryForObject("SELECT state FROM protected_account_activation WHERE invite_id=73", String.class);
  }

}
