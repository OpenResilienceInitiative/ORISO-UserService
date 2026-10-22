package de.caritas.cob.userservice.api.adapters.persistence.password;

import static org.junit.jupiter.api.Assertions.*;

import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims.Binding;
import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims.Claim;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcExistingSetupPasswordClaimsTest {
  private static final String DIGEST = "a".repeat(64);
  private static final String CHANGED = "b".repeat(64);

  @Test
  void sameOwnedInviteAndRequestReusesThePersistedClaimAcrossANewAdapter() throws Exception {
    var f = new Fixture();
    Claim first = assertDoesNotThrow(() -> f.claim(DIGEST));
    assertNotNull(first);
    assertEquals(1, first.version());
    assertEquals(f.binding, first.binding());
    assertEquals(DIGEST, first.requestDigest());
    assertEquals("IN_PROGRESS", f.invitePhase());
    var restarted = new JdbcExistingSetupPasswordClaims(f.jdbc, f.clock);
    Claim retry = f.tx.execute(status -> restarted.claimWithinOwner(f.binding, DIGEST));
    assertEquals(first, retry);
    assertEquals(
        1,
        f.jdbc.queryForObject("select count(*) from identity_setup_password_claim", Integer.class));
    assertEquals(
        Timestamp.valueOf(f.binding.expiresAt()),
        f.jdbc.queryForObject(
            "select authority_valid_until from identity_setup_password_claim", Timestamp.class));
  }

  @Test
  void unknownOutcomeKeepsItsOriginalClaimAndRefusesAChangedSubmission() throws Exception {
    var f = new Fixture();
    Claim first = assertDoesNotThrow(() -> f.claim(DIGEST));
    f.tx.executeWithoutResult(status -> f.journal.markUnknownWithinOwner(first));
    assertEquals("UNKNOWN", f.journalPhase());
    assertEquals(first, f.claim(DIGEST));
    assertThrows(IllegalStateException.class, () -> f.claim(CHANGED));
    assertEquals(first.id().toString(), f.savedClaim());
    assertEquals("UNKNOWN", f.journalPhase());
    assertEquals("IN_PROGRESS", f.invitePhase());
  }

  @Test
  void setupClaimAndInvitationTransitionRollBackInTheirOneOwningTransaction() throws Exception {
    var f = new Fixture();
    assertDoesNotThrow(
        () -> {
          var failure =
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      f.tx.execute(
                          status -> {
                            assertNotNull(f.journal.claimWithinOwner(f.binding, DIGEST));
                            throw new IllegalArgumentException("synthetic owner rollback");
                          }));
          assertEquals("synthetic owner rollback", failure.getMessage());
        });
    assertEquals("PENDING", f.invitePhase());
    assertEquals(
        0,
        f.jdbc.queryForObject("select count(*) from identity_setup_password_claim", Integer.class));
    assertNotNull(f.claim(DIGEST));
  }

  @Test
  void preexistingUnownedInProgressAndForeignOrAbsentTransactionsCannotCreateAClaim()
      throws Exception {
    var f = new Fixture();
    assertThrows(IllegalStateException.class, () -> f.journal.claimWithinOwner(f.binding, DIGEST));
    var foreign = new Fixture();
    assertThrows(
        IllegalStateException.class,
        () -> foreign.tx.execute(status -> f.journal.claimWithinOwner(f.binding, DIGEST)));
    f.jdbc.update("update account_invite set provisioning_status='IN_PROGRESS' where id=11");
    assertThrows(IllegalStateException.class, () -> f.claim(DIGEST));
    assertEquals(
        0,
        f.jdbc.queryForObject("select count(*) from identity_setup_password_claim", Integer.class));
  }

  @Test
  void definiteRejectionNeedsANewActualClaimVersionWithoutExtendingTheDeadline() throws Exception {
    var f = new Fixture();
    Claim first = assertDoesNotThrow(() -> f.claim(DIGEST));
    f.tx.executeWithoutResult(
        status -> {
          f.jdbc.update("update account_invite set provisioning_status='FAILED' where id=11");
          f.journal.rejectWithinOwner(first);
        });
    Claim next = f.claim(CHANGED);
    assertEquals(2, next.version());
    assertNotEquals(first.id(), next.id());
    assertEquals(first.binding().expiresAt(), next.binding().expiresAt());
    assertThrows(
        IllegalStateException.class,
        () -> f.tx.executeWithoutResult(status -> f.journal.requireCurrentWithinOwner(first)));
    assertEquals(next.id().toString(), f.savedClaim());
  }

  @Test
  void acknowledgementAndLocalCompletionCommitOrRollBackTogether() throws Exception {
    var f = new Fixture();
    Claim first = assertDoesNotThrow(() -> f.claim(DIGEST));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.tx.executeWithoutResult(
                status -> {
                  f.complete();
                  f.journal.acknowledgeWithinOwner(first);
                  throw new IllegalArgumentException("synthetic acknowledgement rollback");
                }));
    assertEquals("IN_PROGRESS", f.invitePhase());
    assertEquals("FROZEN", f.journalPhase());
    f.tx.executeWithoutResult(
        status -> {
          f.complete();
          f.journal.acknowledgeWithinOwner(first);
        });
    assertEquals("COMPLETED", f.invitePhase());
    assertEquals("ACKNOWLEDGED", f.journalPhase());
    var afterDeadline =
        new JdbcExistingSetupPasswordClaims(
            f.jdbc, Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));
    Claim historical =
        assertDoesNotThrow(
            () -> f.tx.execute(status -> afterDeadline.claimWithinOwner(f.binding, DIGEST)));
    assertEquals(first, historical);
    assertThrows(
        IllegalStateException.class,
        () -> f.tx.execute(status -> afterDeadline.claimWithinOwner(f.binding, CHANGED)));
    assertEquals("ACKNOWLEDGED", f.journalPhase());
    assertThrows(
        IllegalStateException.class,
        () -> f.tx.executeWithoutResult(status -> f.journal.requireCurrentWithinOwner(first)));
  }

  private static final class Fixture {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    final DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:setup-claim-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(source);
    final TransactionTemplate tx =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    final JdbcExistingSetupPasswordClaims journal =
        new JdbcExistingSetupPasswordClaims(jdbc, clock);
    final Binding binding =
        new Binding(
            11,
            "ordinary-existing-11",
            "c".repeat(64),
            LocalDateTime.of(2026, 10, 23, 0, 0),
            AccountInviteTargetRole.AGENCY_ADMIN,
            42L,
            "existing@example.test",
            "existing@example.test");

    Fixture() throws Exception {
      jdbc.execute(
          "create table account_invite(id bigint primary key,purpose varchar(64),status varchar(32),"
              + "provisioning_status varchar(32),provisioned_user_id varchar(36),token_hash varchar(64),"
              + "expires_at timestamp,target_role varchar(64),tenant_id bigint,setup_bound_username varchar(512),"
              + "recipient_email varchar(512),initial_password_verifier varchar(255),"
              + "provisioning_failure_reason varchar(1024),update_date timestamp)");
      jdbc.update(
          "insert into account_invite values(11,'EXISTING_ACCOUNT_SETUP','EMAIL_SENT','PENDING',"
              + "?,?,?,?,?,?,?,'synthetic-salted-verifier',null,null)",
          binding.accountId(),
          binding.tokenHash(),
          Timestamp.valueOf(binding.expiresAt()),
          binding.role().name(),
          binding.tenantId(),
          binding.username(),
          binding.email());
      try (var connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource(
                "db/changelog/changeset/20261022_identity_setup_password_claim_367/migrate.sql"));
      }
    }

    Claim claim(String digest) {
      return tx.execute(status -> journal.claimWithinOwner(binding, digest));
    }

    String invitePhase() {
      return jdbc.queryForObject(
          "select provisioning_status from account_invite where id=11", String.class);
    }

    String journalPhase() {
      return jdbc.queryForObject(
          "select status from identity_setup_password_claim where invite_id=11", String.class);
    }

    String savedClaim() {
      return jdbc.queryForObject(
          "select claim_id from identity_setup_password_claim where invite_id=11", String.class);
    }

    void complete() {
      jdbc.update(
          "update account_invite set status='ACCEPTED',provisioning_status='COMPLETED',"
              + "initial_password_verifier=null where id=11");
    }
  }
}
