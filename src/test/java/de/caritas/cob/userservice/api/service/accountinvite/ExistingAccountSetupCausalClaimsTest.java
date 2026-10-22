package de.caritas.cob.userservice.api.service.accountinvite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.persistence.password.JdbcExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual public service + owning SQL claim/ACK; the provider/digest port is an explicit test
 * double.
 */
class ExistingAccountSetupCausalClaimsTest {
  @Test
  void actualOwnedSetupUsesOnlyTheTypedHandleAndClosesItAfterTheRequest() throws Exception {
    var f = new Fixture();
    assertDoesNotThrow(() -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals("COMPLETED", f.phase());
    assertEquals("ACKNOWLEDGED", f.journalPhase());
    assertEquals(1, f.passwords.effects.size());
    assertEquals(1, f.passwords.supportChecks);
    assertDoesNotThrow(() -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals(1, f.passwords.calls.size());
    assertThrows(RuntimeException.class, () -> f.service.confirm(Fixture.TOKEN, "different"));
    verify(f.invites, times(1))
        .completeExistingAccountSetup(
            anyLong(), any(), any(), any(), any(), any(), any(), anyString(), any());
    assertThrows(
        ScopedIdentityCommandRequiredException.class, f.passwords.lastHandle::checkedClaim);
    verifyNoInteractions(f.identities, f.roles, f.requirement);

    var unsupported = new Fixture();
    unsupported.passwords.supported = false;
    assertThrows(
        ScopedIdentityCommandRequiredException.class,
        () -> unsupported.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals("PENDING", unsupported.phase());
    assertEquals(
        0,
        unsupported.jdbc.queryForObject(
            "select count(*) from identity_setup_password_claim", Integer.class));
    var ambient = new Fixture();
    new org.springframework.transaction.support.TransactionTemplate(ambient.transactions)
        .executeWithoutResult(
            status ->
                assertThrows(
                    ScopedIdentityCommandRequiredException.class,
                    () -> ambient.service.confirm(Fixture.TOKEN, "new-one")));
    assertEquals("PENDING", ambient.phase());
    assertEquals(
        0,
        ambient.jdbc.queryForObject(
            "select count(*) from identity_setup_password_claim", Integer.class));
  }

  @Test
  void unknownOutcomeKeepsTheActualClaimAndOnlyItsSameSubmissionCanResume() throws Exception {
    var f = new Fixture();
    f.passwords.unknownOnce = true;
    assertThrows(IllegalStateException.class, () -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals("IN_PROGRESS", f.phase());
    assertEquals("UNKNOWN", f.journalPhase());
    var first = f.passwords.calls.getFirst();
    assertThrows(RuntimeException.class, () -> f.service.confirm(Fixture.TOKEN, "different"));
    assertEquals(1, f.passwords.calls.size());
    assertDoesNotThrow(() -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals(List.of(first, first), f.passwords.calls);
    assertEquals(1, f.passwords.effects.size());
    assertEquals("ACKNOWLEDGED", f.journalPhase());
    verifyNoInteractions(f.identities, f.roles, f.requirement);
  }

  @Test
  void unchangedPasswordRejectionReleasesOnlyTheOriginalClaimBeforeANewVersion() throws Exception {
    var f = new Fixture();
    assertThrows(BadRequestException.class, () -> f.service.confirm(Fixture.TOKEN, "initial"));
    assertEquals("FAILED", f.phase());
    assertEquals("REJECTED", f.journalPhase());
    assertTrue(f.passwords.calls.isEmpty());
    assertDoesNotThrow(() -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals(2, f.passwords.calls.getFirst().version());
    assertEquals(f.deadline, f.passwords.calls.getFirst().binding().expiresAt());
    assertEquals("ACKNOWLEDGED", f.journalPhase());
  }

  @Test
  void localCompletionRollbackRetainsTheSameClaimWithoutRepeatingTheProviderEffect()
      throws Exception {
    var f = new Fixture();
    f.failCompletionOnce = true;
    assertThrows(IllegalStateException.class, () -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals("IN_PROGRESS", f.phase());
    assertEquals("UNKNOWN", f.journalPhase());
    assertEquals(1, f.passwords.effects.size());
    assertDoesNotThrow(() -> f.service.confirm(Fixture.TOKEN, "new-one"));
    assertEquals(f.passwords.calls.getFirst(), f.passwords.calls.getLast());
    assertEquals(1, f.passwords.effects.size());
    assertEquals("COMPLETED", f.phase());
    assertEquals("ACKNOWLEDGED", f.journalPhase());
  }

  private static final class Fixture {
    static final String TOKEN = "synthetic-existing-setup-token";
    final LocalDateTime deadline = LocalDateTime.now().withNano(0).plusDays(1);
    final String tokenHash = AccountInviteService.hash(TOKEN);
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:setup-service-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
    final AccountInviteRepository invites = mock(AccountInviteRepository.class);
    final AdminRepository admins = mock(AdminRepository.class);
    final ConsultantRepository consultants = mock(ConsultantRepository.class);
    final IdentityProfileLookup identities = mock(IdentityProfileLookup.class);
    final IdentityRoleLookup roles = mock(IdentityRoleLookup.class);
    final IdentityPasswordChangeRequirement requirement =
        mock(IdentityPasswordChangeRequirement.class);
    final InitialPasswordVerifier initial = mock(InitialPasswordVerifier.class);
    final TypedPasswords passwords = new TypedPasswords();
    final ExistingAccountSetupService service =
        new ExistingAccountSetupService(
            invites,
            admins,
            consultants,
            identities,
            roles,
            requirement,
            initial,
            passwords,
            transactions);
    boolean failCompletionOnce;

    Fixture() throws Exception {
      jdbc.execute(
          "create table account_invite(id bigint primary key,purpose varchar(64),status varchar(32),"
              + "provisioning_status varchar(32),provisioned_user_id varchar(36),token_hash varchar(64),"
              + "expires_at timestamp,target_role varchar(64),tenant_id bigint,setup_bound_username varchar(512),"
              + "recipient_email varchar(512),initial_password_verifier varchar(255),"
              + "provisioning_failure_reason varchar(1024),update_date timestamp)");
      jdbc.update(
          "insert into account_invite values(11,'EXISTING_ACCOUNT_SETUP','EMAIL_SENT','PENDING',"
              + "'ordinary-admin-11',?,?,'AGENCY_ADMIN',42,'existing@example.test','existing@example.test',"
              + "'synthetic-salted-verifier',null,null)",
          tokenHash,
          Timestamp.valueOf(deadline));
      try (var connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_setup_password_claim_367/migrate.sql"));
      }
      service.setSetupPasswordClaims(
          new JdbcExistingSetupPasswordClaims(jdbc, Clock.systemDefaultZone()));
      when(invites.findByTokenHash(anyString()))
          .thenAnswer(a -> read("token_hash", a.getArgument(0)));
      when(invites.findByIdForUpdate(anyLong())).thenAnswer(a -> read("id", a.getArgument(0)));
      when(admins.findByIdAndType("ordinary-admin-11", Admin.AdminType.AGENCY))
          .thenReturn(
              Optional.of(
                  Admin.builder()
                      .id("ordinary-admin-11")
                      .type(Admin.AdminType.AGENCY)
                      .tenantId(42L)
                      .firstName("Existing")
                      .lastName("Admin")
                      .username("existing@example.test")
                      .email("existing@example.test")
                      .build()));
      when(initial.matches(anyString(), anyString()))
          .thenAnswer(a -> "initial".equals(a.getArgument(0)));
      when(invites.releaseExistingAccountSetup(
              anyLong(), any(), any(), any(), any(), anyString(), any()))
          .thenAnswer(
              a ->
                  jdbc.update(
                      "update account_invite set provisioning_status='FAILED' where id=11 and provisioning_status='IN_PROGRESS'"));
      when(invites.recordIndeterminateExistingAccountSetup(
              anyLong(), any(), any(), any(), anyString(), any()))
          .thenAnswer(
              a ->
                  jdbc.update(
                      "update account_invite set provisioning_failure_reason='SETUP_OUTCOME_INDETERMINATE' where id=11 and provisioning_status='IN_PROGRESS'"));
      when(invites.completeExistingAccountSetup(
              anyLong(), any(), any(), any(), any(), any(), any(), anyString(), any()))
          .thenAnswer(
              a -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                int changed =
                    jdbc.update(
                        "update account_invite set status='ACCEPTED',provisioning_status='COMPLETED',initial_password_verifier=null where id=11 and status='EMAIL_SENT' and provisioning_status='IN_PROGRESS'");
                if (failCompletionOnce) {
                  failCompletionOnce = false;
                  throw new IllegalStateException("synthetic owning SQL failure");
                }
                return changed;
              });
    }

    Optional<AccountInvite> read(String field, Object value) {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      var rows =
          jdbc.query(
              "select * from account_invite where " + field + "=? for update",
              (rs, n) ->
                  AccountInvite.builder()
                      .id(rs.getLong("id"))
                      .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                      .status(AccountInviteStatus.valueOf(rs.getString("status")))
                      .provisioningStatus(
                          AccountInviteProvisioningStatus.valueOf(
                              rs.getString("provisioning_status")))
                      .provisioningFailureReason(rs.getString("provisioning_failure_reason"))
                      .provisionedUserId(rs.getString("provisioned_user_id"))
                      .tokenHash(rs.getString("token_hash"))
                      .expiresAt(rs.getTimestamp("expires_at").toLocalDateTime())
                      .targetRole(AccountInviteTargetRole.AGENCY_ADMIN)
                      .tenantId(42L)
                      .setupBoundUsername("existing@example.test")
                      .recipientEmail("existing@example.test")
                      .initialPasswordVerifier(rs.getString("initial_password_verifier"))
                      .build(),
              value);
      return rows.stream().findFirst();
    }

    String phase() {
      return jdbc.queryForObject(
          "select provisioning_status from account_invite where id=11", String.class);
    }

    String journalPhase() {
      return jdbc.queryForObject(
          "select status from identity_setup_password_claim where invite_id=11", String.class);
    }
  }

  /** No native or crypto acceptance: explicit port double preserves an exact synthetic outcome. */
  private static final class TypedPasswords implements IdentityPasswordUpdater {
    final List<ExistingSetupPasswordClaims.Claim> calls = new ArrayList<>();
    final Set<UUID> effects = new HashSet<>();
    ExistingAccountSetupService.ClaimedSetup lastHandle;
    int supportChecks;
    boolean unknownOnce;
    boolean supported = true;

    @Override
    public boolean requiresCausalPasswordClaims() {
      return true;
    }

    @Override
    public void requireExistingSetupPasswordSupport() {
      supportChecks++;
      if (!supported) throw new ScopedIdentityCommandRequiredException();
    }

    @Override
    public String existingSetupRequestDigest(
        ExistingSetupPasswordClaims.Binding binding, String password) {
      assertNotNull(binding);
      return ("initial".equals(password) ? "b" : "new-one".equals(password) ? "c" : "d").repeat(64);
    }

    @Override
    public void updateExistingSetupPassword(
        ExistingAccountSetupService.ClaimedSetup handle, String password) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      var claim = handle.checkedClaim();
      lastHandle = handle;
      calls.add(claim);
      var crossThread = new AtomicReference<Throwable>();
      Thread other =
          new Thread(
              () -> {
                try {
                  handle.checkedClaim();
                } catch (Throwable failure) {
                  crossThread.set(failure);
                }
              });
      other.start();
      try {
        other.join(2000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException();
      }
      assertFalse(other.isAlive());
      assertInstanceOf(ScopedIdentityCommandRequiredException.class, crossThread.get());
      effects.add(claim.id());
      if (unknownOnce) {
        unknownOnce = false;
        throw new IllegalStateException("synthetic unknown reply");
      }
    }

    @Override
    public void updatePassword(String id, String password) {
      fail("target-only updater used");
    }

    @Override
    public void updateTemporaryPassword(String id, String password) {
      fail("temporary updater used");
    }
  }
}
