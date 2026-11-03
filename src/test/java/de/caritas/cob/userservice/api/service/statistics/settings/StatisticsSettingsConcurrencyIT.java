package de.caritas.cob.userservice.api.service.statistics.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.StatisticsSettingsLock;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingsLockRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Two saves of the same revision at the same moment (UserService#1562): one must be refused. The
 * first save is held open, uncommitted, inside its own transaction while the second one starts -
 * the exact window a plain row count leaves open. The service runs behind the real transaction
 * annotations; the held save and the early read of the second one hook into the authenticated user
 * the service asks, so both happen inside the transaction the service itself opened.
 *
 * <p>The H2 of the testing profile reads committed data only. On MariaDB (REPEATABLE READ) the
 * early read would pin the snapshot; run this class against MariaDB to cover that.
 */
@SpringBootTest
@ActiveProfiles("testing")
class StatisticsSettingsConcurrencyIT {

  private static final long WAIT_SECONDS = 20;

  @Autowired private StatisticsSettingChangeRepository repository;
  @Autowired private StatisticsSettingsLockRepository locks;
  @Autowired private PlatformTransactionManager transactionManager;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    locks.deleteAll();
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    repository.deleteAll();
    locks.deleteAll();
  }

  @Test
  void twoSavesOfTheSameRevision_Should_NotBothSucceed_WhenTheLockRowExists() throws Exception {
    locks.saveAndFlush(new StatisticsSettingsLock(StatisticsSettingsLock.ID));

    assertSecondSaveIsRefused(false);
  }

  @Test
  void twoSavesOfTheSameRevision_Should_NotBothSucceed_WhenTheLockRowIsMissing() throws Exception {
    assertSecondSaveIsRefused(false);
  }

  @Test
  void aReadBeforeTheSave_Should_NotLetTheSecondSaveSeeAnOldRevision() throws Exception {
    locks.saveAndFlush(new StatisticsSettingsLock(StatisticsSettingsLock.ID));

    assertSecondSaveIsRefused(true);
  }

  @Test
  void aSecondSave_Should_Succeed_WithTheNewRevision_AfterTheFirstOneIsDone() {
    service(admin()).updateSettings(Map.of("agencyMinimumCounsellors", "2"), 0L);

    var saved = service(admin()).updateSettings(Map.of("agencyMinimumCounsellors", "3"), 1L);

    assertThat(saved.revision()).isEqualTo(2L);
  }

  private void assertSecondSaveIsRefused(boolean secondReadsFirst) throws Exception {
    var firstHasSaved = new CountDownLatch(1);
    var letFirstCommit = new CountDownLatch(1);
    var firstAdmin =
        new HookedAdmin(
            () -> {},
            () -> {
              firstHasSaved.countDown();
              await(letFirstCommit);
            });
    var secondAdmin = new HookedAdmin(secondReadsFirst ? repository::count : () -> {}, () -> {});

    var first =
        pool.submit(
            () -> service(firstAdmin).updateSettings(Map.of("agencyMinimumCounsellors", "2"), 0L));
    assertThat(firstHasSaved.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    var second =
        pool.submit(
            () -> service(secondAdmin).updateSettings(Map.of("agencyMinimumCounsellors", "3"), 0L));
    // the second save has to wait for the first; without a lock it would be done by now
    assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    letFirstCommit.countDown();

    first.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertThatThrownBy(() -> second.get(WAIT_SECONDS, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ConflictException.class);
    assertThat(repository.findAllByOrderByIdAsc())
        .extracting(change -> change.getNewValue())
        .containsExactly("2");
  }

  /** The service behind its transaction annotations, as the application context builds it. */
  private StatisticsSettingsService service(AuthenticatedUser admin) {
    var proxy =
        new ProxyFactory(
            new StatisticsSettingsService(repository, locks, admin, Clock.systemUTC()));
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    return (StatisticsSettingsService) proxy.getProxy();
  }

  private static AuthenticatedUser admin() {
    return new HookedAdmin(() -> {}, () -> {});
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** A platform admin that runs a hook at the first thing the service asks, and when it saves. */
  private static class HookedAdmin extends AuthenticatedUser {
    private final Runnable beforeAnythingElse;
    private final Runnable whileSaving;

    HookedAdmin(Runnable beforeAnythingElse, Runnable whileSaving) {
      this.beforeAnythingElse = beforeAnythingElse;
      this.whileSaving = whileSaving;
      setUserId("platform-admin-id");
      setUsername("platform.admin");
      setAccessToken("token");
      setTenantId(0L);
      setRoles(Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
    }

    @Override
    public boolean isPlatformAdmin() {
      beforeAnythingElse.run();
      return super.isPlatformAdmin();
    }

    @Override
    public String getUserId() {
      whileSaving.run();
      return super.getUserId();
    }
  }
}
