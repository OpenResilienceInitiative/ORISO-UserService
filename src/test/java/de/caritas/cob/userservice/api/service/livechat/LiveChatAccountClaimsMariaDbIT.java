package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  LiveChatInactivityFence.class,
  LiveChatFinishExecutor.class,
  LiveChatAccountClaimService.class,
  LiveChatAccountConversionExecutor.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiveChatAccountClaimsMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

  @Autowired private UserRepository users;
  @Autowired private LiveChatAccountClaimService claims;
  @Autowired private LiveChatAccountClaimRepository records;
  @Autowired private LiveChatAccountConversionExecutor executor;
  @MockitoBean private de.caritas.cob.userservice.api.port.out.IdentityClient identities;
  @MockitoBean private de.caritas.cob.userservice.api.port.out.IdentityReactivator reactivator;
  @MockitoBean private de.caritas.cob.userservice.api.port.out.IdentityDeactivator deactivator;
  @Autowired private LiveChatFinishExecutor finishExecutor;

  @MockitoBean
  private de.caritas.cob.userservice.api.port.out.IdentityAuthentication authentication;

  @Autowired private PlatformTransactionManager transactions;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoBean private SessionRepository sessions;

  @MockitoBean
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
      effects;

  @BeforeEach
  void setup() {
    when(effects.currentRoles(any()))
        .thenReturn(
            java.util.Set.of(
                de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                    .Role.ASKER));
    jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", "synthetic-claim-guest");
    users.deleteById("synthetic-claim-guest");
    var user = new User();
    user.setUserId("synthetic-claim-guest");
    user.setUsername("synthetic-claim-guest");
    user.setEmail("synthetic-claim-guest@example.invalid");
    user.setEncourage2fa(false);
    user.setMagicLinkLoginEnabled(false);
    user.setLanguageCode(LanguageCode.de);
    user.setCreateDate(java.time.LocalDateTime.now());
    user.setUpdateDate(java.time.LocalDateTime.now());
    users.save(user);
    jdbc.update(
        "INSERT INTO account_inactivity(identity_id, tenant_id, assigned_months, revision, last_activity, due_at, status, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        "synthetic-claim-guest",
        1L,
        24,
        0L,
        java.time.LocalDateTime.now(),
        java.time.LocalDateTime.now().plusDays(365),
        "ACTIVE",
        0);
    var anonymous = new Session();
    anonymous.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    when(sessions.findByUser(any())).thenReturn(List.of(anonymous));
  }

  @Test
  void promotionCommittedWhileCleanupWaitsMakesCleanupSkip() throws Exception {
    assertRace(
        LiveChatAccountLifecycle.PROMOTION_PENDING,
        () -> assertThat(claims.claimCleanup("synthetic-claim-guest")).isFalse());
  }

  @Test
  void cleanupCommittedWhilePromotionWaitsMakesPromotionRefuse() throws Exception {
    assertRace(
        LiveChatAccountLifecycle.CLEANUP_STARTED,
        () ->
            assertThatThrownBy(
                    () ->
                        claims.claimConversion(
                            "synthetic-claim-guest",
                            "123e4567-e89b-12d3-a456-426614174000",
                            "chosen-password-123"))
                .isInstanceOf(RuntimeException.class));
  }

  @Test
  void finishClaimWinningPreventsPromotionBeforeDisableCompletes() throws Exception {
    assertRace(
        LiveChatAccountLifecycle.FINISH_PENDING,
        () ->
            assertThatThrownBy(
                    () ->
                        claims.claimConversion(
                            "synthetic-claim-guest",
                            "123e4567-e89b-12d3-a456-426614174000",
                            "chosen-password-123"))
                .isInstanceOf(
                    de.caritas.cob.userservice.api.exception.httpresponses.ConflictException
                        .class));
  }

  @Test
  void promotionClaimWinningPreventsNormalFinishFromDisablingIdentity() throws Exception {
    assertRace(
        LiveChatAccountLifecycle.PROMOTION_PENDING,
        () ->
            assertThat(claims.claimNormalFinish("synthetic-claim-guest"))
                .isEqualTo(LiveChatAccountClaimService.FinishMode.KEEP_IDENTITY));
  }

  @Test
  void promotionWinnerPreventsConcurrentRoleAddition() throws Exception {
    var service =
        new de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService(
            jdbc, transactions, java.time.Clock.systemUTC(), effects);
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    assertRace(
        LiveChatAccountLifecycle.PROMOTION_PENDING,
        () ->
            assertThatThrownBy(
                    () ->
                        service.withRoleMutation("synthetic-claim-guest", () -> changed.set(true)))
                .isInstanceOf(IllegalStateException.class));
    assertThat(changed).isFalse();
  }

  @Test
  void roleAdditionWinnerMakesStaleAskerTokenPromotionRefuse() throws Exception {
    var id = "synthetic-claim-guest";
    var roles =
        new java.util.concurrent.atomic.AtomicReference<>(
            java.util.Set.of(
                de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                    .Role.ASKER));
    when(effects.currentRoles(id)).thenAnswer(invocation -> roles.get());
    var service =
        new de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService(
            jdbc, transactions, java.time.Clock.systemUTC(), effects);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var roleChange =
          pool.submit(
              () ->
                  service.withRoleMutation(
                      id,
                      () -> {
                        entered.countDown();
                        try {
                          if (!release.await(15, TimeUnit.SECONDS))
                            throw new IllegalStateException("timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        roles.set(
                            java.util.Set.of(
                                de.caritas.cob.userservice.api.workflow.accountinactivity
                                    .AccountInactivityEffects.Role.ASKER,
                                de.caritas.cob.userservice.api.workflow.accountinactivity
                                    .AccountInactivityEffects.Role.CONSULTANT));
                      }));
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      var promotion =
          pool.submit(
              () ->
                  claims.claimConversion(
                      id, "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123"));
      try {
        assertThatThrownBy(() -> promotion.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      roleChange.get(15, TimeUnit.SECONDS);
      assertThatThrownBy(() -> promotion.get(15, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class);
      assertThat(records.findById(id)).isEmpty();
    }
  }

  @Test
  void inactivityWinnerMakesPromotionWaitThenRefuse() throws Exception {
    var id = "synthetic-claim-guest";
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var inactivity =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForObject(
                                "SELECT status FROM account_inactivity WHERE identity_id=? FOR UPDATE",
                                String.class,
                                id);
                            jdbc.update(
                                "UPDATE account_inactivity SET status='SUSPENDING' WHERE identity_id=?",
                                id);
                            entered.countDown();
                            try {
                              if (!release.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("timeout");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      var promotion =
          pool.submit(
              () ->
                  claims.claimConversion(
                      id, "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123"));
      try {
        assertThatThrownBy(() -> promotion.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      inactivity.get(15, TimeUnit.SECONDS);
      assertThatThrownBy(() -> promotion.get(15, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class);
      assertThat(records.findById(id)).isEmpty();
    }
  }

  @Test
  void promotionWinnerMakesInactivityWaitThenRefuse() throws Exception {
    var id = "synthetic-claim-guest";
    var service =
        new de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService(
            jdbc, transactions, java.time.Clock.systemUTC(), effects);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var promotion =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForObject(
                                "SELECT status FROM account_inactivity WHERE identity_id=? FOR UPDATE",
                                String.class,
                                id);
                            users.findByUserIdForUpdate(id).orElseThrow();
                            records.save(
                                new LiveChatAccountClaim(
                                    id, LiveChatAccountLifecycle.PROMOTION_PENDING));
                            entered.countDown();
                            try {
                              if (!release.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("timeout");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      var suspension = pool.submit(() -> service.suspend(id));
      try {
        assertThatThrownBy(() -> suspension.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      promotion.get(15, TimeUnit.SECONDS);
      assertThatThrownBy(() -> suspension.get(15, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class);
      assertThat(service.snapshot(id).orElseThrow().status())
          .isEqualTo(
              de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
                  .Status.ACTIVE);
    }
  }

  @Test
  void intentionalDeletionWinnerPreventsSubsequentPromotion() {
    var id = "synthetic-claim-guest";
    claims.claimIntentionalDeletion(id);
    assertThatThrownBy(
            () ->
                claims.claimConversion(
                    id, "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
    assertThat(records.findById(id).orElseThrow().getLifecycle())
        .isEqualTo(LiveChatAccountLifecycle.CLEANUP_STARTED);
  }

  @Test
  void explicitDeletionLosingToPromotionFailsClosed() throws Exception {
    assertRace(
        LiveChatAccountLifecycle.PROMOTION_PENDING,
        () ->
            assertThatThrownBy(() -> claims.claimIntentionalDeletion("synthetic-claim-guest"))
                .isInstanceOf(
                    de.caritas.cob.userservice.api.exception.httpresponses.ConflictException
                        .class));
  }

  @Test
  void concurrentDifferentPasswordCannotOverwriteSuccessfulConversion() throws Exception {
    var id = "synthetic-claim-guest";
    var intent = "123e4567-e89b-12d3-a456-426614174000";
    var password = "chosen-password-123";
    claims.claimConversion(id, intent, password);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(authentication.verifyPasswordIgnoringSecondFactor(
            any(), org.mockito.ArgumentMatchers.eq(password)))
        .thenReturn(false, true);
    when(identities.changePassword(id, password))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("release timed out");
              return true;
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> executor.execute(id, "guest-name", intent, password));
      assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
      var second =
          pool.submit(
              () -> {
                claims.claimConversion(id, intent, "different-password-123");
                executor.execute(id, "guest-name", intent, "different-password-123");
              });
      try {
        assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      first.get(15, TimeUnit.SECONDS);
      assertThatThrownBy(() -> second.get(15, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class);
      org.mockito.Mockito.verify(identities, org.mockito.Mockito.times(1))
          .changePassword(any(), any());
      assertThat(records.findById(id).orElseThrow().getLifecycle())
          .isEqualTo(LiveChatAccountLifecycle.PROMOTED);
    }
  }

  @Test
  void dueCleanupRecoversFinishCrashAfterExternalDisableBeforeMarkerCommit() {
    var id = "synthetic-claim-guest";
    assertThat(claims.claimNormalFinish(id))
        .isEqualTo(LiveChatAccountClaimService.FinishMode.CONVERTIBLE_FINISH);
    var disabled = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              disabled.set(true);
              throw new IllegalStateException("crash after disable");
            })
        .when(deactivator)
        .deactivateUser(id);
    assertThatThrownBy(() -> finishExecutor.disableAndMarkFinished(id))
        .isInstanceOf(IllegalStateException.class);
    assertThat(disabled).isTrue();
    assertThat(records.findById(id).orElseThrow().getLifecycle())
        .isEqualTo(LiveChatAccountLifecycle.FINISH_PENDING);
    // Caller is the existing overdue scheduler/batch; this claim does not select candidates itself.
    assertThat(claims.claimCleanup(id)).isTrue();
    assertThat(records.findById(id).orElseThrow().getLifecycle())
        .isEqualTo(LiveChatAccountLifecycle.CLEANUP_STARTED);
    assertThatThrownBy(
            () ->
                claims.claimConversion(
                    id, "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
  }

  @Test
  void missingInactivityRowCleanupRacesEnrollmentAndPromotionWithoutLosingDeletionClaim()
      throws Exception {
    var id = "synthetic-claim-guest";
    jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", id);
    var cleanupLocked = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    var fence = new LiveChatInactivityFence(jdbc, effects);
    try (var pool = Executors.newFixedThreadPool(3)) {
      var cleanup =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            assertThat(fence.lockIfPresent(id)).isEmpty();
                            users.findByUserIdForUpdate(id).orElseThrow();
                            records.save(
                                new LiveChatAccountClaim(
                                    id, LiveChatAccountLifecycle.CLEANUP_STARTED));
                            cleanupLocked.countDown();
                            try {
                              if (!releaseCleanup.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("release timed out");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(cleanupLocked.await(15, TimeUnit.SECONDS)).isTrue();
      var enrollment =
          pool.submit(
              () ->
                  jdbc.update(
                      "INSERT INTO account_inactivity(identity_id, tenant_id, assigned_months, revision, last_activity, due_at, status, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                      id,
                      1L,
                      24,
                      0L,
                      java.time.LocalDateTime.now(),
                      java.time.LocalDateTime.now().plusDays(365),
                      "ACTIVE",
                      0));
      var promotion =
          pool.submit(
              () ->
                  claims.claimConversion(
                      id, "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123"));
      releaseCleanup.countDown();
      cleanup.get(15, TimeUnit.SECONDS);
      enrollment.get(15, TimeUnit.SECONDS);
      assertThatThrownBy(() -> promotion.get(15, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class);
      assertThatThrownBy(
              () ->
                  claims.claimConversion(
                      id, "123e4567-e89b-12d3-a456-426614174000", "chosen-password-123"))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class);
      assertThat(records.findById(id).orElseThrow().getLifecycle())
          .isEqualTo(LiveChatAccountLifecycle.CLEANUP_STARTED);
    }
  }

  @Test
  void explicitUserDeletionCascadesTheClaimRow() {
    assertThat(claims.claimCleanup("synthetic-claim-guest")).isTrue();
    users.deleteById("synthetic-claim-guest");
    assertThat(records.findById("synthetic-claim-guest")).isEmpty();
  }

  @Test
  void staleUserUpdateCannotEraseTheSeparateClaim() {
    var stale = users.findById("synthetic-claim-guest").orElseThrow();
    assertThat(claims.claimCleanup("synthetic-claim-guest")).isTrue();
    stale.setEmail("updated-synthetic@example.invalid");
    users.save(stale);
    assertThat(records.findById("synthetic-claim-guest").orElseThrow().getLifecycle())
        .isEqualTo(LiveChatAccountLifecycle.CLEANUP_STARTED);
  }

  private void assertRace(LiveChatAccountLifecycle winner, Runnable contender) throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            var user =
                                users.findByUserIdForUpdate("synthetic-claim-guest").orElseThrow();
                            records.save(new LiveChatAccountClaim(user.getUserId(), winner));
                            locked.countDown();
                            try {
                              if (!release.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("release timed out");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
      var second = pool.submit(contender);
      try {
        assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
      assertThat(records.findById("synthetic-claim-guest").orElseThrow().getLifecycle())
          .isEqualTo(winner);
    }
  }
}
