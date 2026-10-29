package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.persistence.password.JdbcExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.port.out.ExistingSetupPasswordClaims.Binding;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import java.sql.Timestamp;
import java.time.*;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@ResourceLock("java.util.TimeZone.default")
class ExistingSetupFactoryClockTest {
  @ParameterizedTest
  @ValueSource(strings = {"UTC", "America/New_York", "Europe/Prague"})
  void factoryUsesTheOriginalInvitationWallTimeForFreshnessAndHeldRecovery(String zoneName)
      throws Exception {
    var previous = TimeZone.getDefault();
    var zone = ZoneId.of(zoneName);
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      var now = Instant.parse("2026-10-28T14:00:00Z");
      var expiry = LocalDateTime.ofInstant(now.plusSeconds(7200), zone);
      var source =
          new DriverManagerDataSource(
              "jdbc:h2:mem:factory-clock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      var jdbc = new JdbcTemplate(source);
      var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
      var config =
          new AccountLifecycleCallerConfiguration(
              new MockEnvironment()
                  .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
                  .withProperty(
                      "ORISO_ACCOUNT_LIFECYCLE_GROUPS", "{\"/human-roles/user\":[\"user\"]}")
                  .withProperty("ORISO_IDENTITY_HUMAN_CLIENT_IDS", "[\"human-client\"]"));
      jdbc.execute(
          "create table account_invite(id bigint primary key,purpose varchar(64),status varchar(32),provisioning_status varchar(32),provisioned_user_id varchar(36),token_hash varchar(64),expires_at timestamp,target_role varchar(64),tenant_id bigint,setup_bound_username varchar(512),recipient_email varchar(512),initial_password_verifier varchar(255),provisioning_failure_reason varchar(1024),update_date timestamp)");
      try (var connection = source.getConnection()) {
        for (String migration :
            new String[] {
              "20261022_identity_setup_password_claim_367",
              "20261023_identity_setup_password_original_sql_367"
            })
          ScriptUtils.executeSqlScript(
              connection,
              new ClassPathResource("db/changelog/changeset/" + migration + "/migrate.sql"));
      }
      var binding =
          new Binding(
              11,
              "existing-account",
              "c".repeat(64),
              expiry,
              AccountInviteTargetRole.AGENCY_ADMIN,
              42L,
              "existing@example.test",
              "existing@example.test");
      jdbc.update(
          "insert into account_invite values(11,'EXISTING_ACCOUNT_SETUP','EMAIL_SENT','PENDING',?,?,?,?,?,?,?,'synthetic-salted-verifier',null,null)",
          binding.accountId(),
          binding.tokenHash(),
          Timestamp.valueOf(expiry),
          binding.role().name(),
          binding.tenantId(),
          binding.username(),
          binding.email());
      String digest = "a".repeat(64);
      var journal = factoryAt(config, jdbc, now, zone);
      var claim =
          assertDoesNotThrow(() -> tx.execute(status -> journal.claimWithinOwner(binding, digest)));
      tx.executeWithoutResult(
          status -> {
            journal.requireCurrentWithinOwner(claim);
            journal.markUnknownWithinOwner(claim);
          });
      var original =
          jdbc.queryForMap("select * from identity_setup_password_claim where invite_id=11");
      assertEquals(Timestamp.valueOf(expiry), original.get("AUTHORITY_VALID_UNTIL"));
      var restarted = factoryAt(config, jdbc, now.plusSeconds(60), zone);
      assertEquals(claim, tx.execute(status -> restarted.claimWithinOwner(binding, digest)));
      assertEquals(
          original,
          jdbc.queryForMap("select * from identity_setup_password_claim where invite_id=11"));
      var codec = mock(AccountLifecycleCodec.class);
      when(codec.passwordResetRecipientDigest(binding.email())).thenReturn("d".repeat(64));
      var nativeAdapter = mock(ClaimedPasswordMaintenance.class, CALLS_REAL_METHODS);
      ReflectionTestUtils.setField(nativeAdapter, "codec", codec);
      Object nativeBinding =
          ReflectionTestUtils.invokeMethod(nativeAdapter, "existingSetupBinding", claim);
      assertEquals(
          now.plusSeconds(7200).getEpochSecond(),
          (long) ReflectionTestUtils.invokeMethod(nativeBinding, "expires"));
      var expired = factoryAt(config, jdbc, now.plusSeconds(7200), zone);
      assertThrows(
          IllegalStateException.class,
          () -> tx.execute(status -> expired.claimWithinOwner(binding, digest)));
      assertThrows(
          IllegalStateException.class,
          () -> tx.executeWithoutResult(status -> expired.requireCurrentWithinOwner(claim)));
      assertEquals(
          claim, tx.execute(status -> expired.recoverWithinOwner(binding, digest).orElseThrow()));
      tx.executeWithoutResult(status -> expired.requireRecoveryWithinOwner(claim));
      assertEquals(
          original,
          jdbc.queryForMap("select * from identity_setup_password_claim where invite_id=11"));
      var retentionBoundary =
          factoryAt(config, jdbc, expiry.plusDays(7).atZone(zone).toInstant(), zone);
      assertThrows(
          IllegalStateException.class,
          () ->
              tx.executeWithoutResult(
                  status -> retentionBoundary.requireRecoveryWithinOwner(claim)));
      assertEquals(
          original,
          jdbc.queryForMap("select * from identity_setup_password_claim where invite_id=11"));
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  private JdbcExistingSetupPasswordClaims factoryAt(
      AccountLifecycleCallerConfiguration config, JdbcTemplate jdbc, Instant now, ZoneId zone) {
    var utc = Clock.fixed(now, ZoneOffset.UTC);
    var local = Clock.fixed(now, zone);
    try (var clocks = mockStatic(Clock.class, CALLS_REAL_METHODS)) {
      clocks.when(Clock::systemUTC).thenReturn(utc);
      clocks.when(Clock::systemDefaultZone).thenReturn(local);
      return config.existingSetupPasswordClaims(jdbc);
    }
  }
}
