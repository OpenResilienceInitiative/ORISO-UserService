package de.caritas.cob.userservice.api.service.statistics.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingChangeRepository;
import de.caritas.cob.userservice.api.port.out.StatisticsSettingsLockRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two saves of the same revision at the same moment (UserService#1562): one must be refused. The
 * first save is held open, uncommitted, while the second one starts - the exact window a plain row
 * count leaves open. The service is built by hand so each save runs in a transaction this test
 * controls.
 */
@SpringBootTest
@ActiveProfiles("testing")
class StatisticsSettingsConcurrencyIT {

  private static final long WAIT_SECONDS = 20;

  @Autowired private StatisticsSettingChangeRepository repository;
  @Autowired private StatisticsSettingsLockRepository locks;
  @Autowired private PlatformTransactionManager transactionManager;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);
  private StatisticsSettingsService service;
  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    var admin = new AuthenticatedUser();
    admin.setUserId("platform-admin-id");
    admin.setUsername("platform.admin");
    admin.setAccessToken("token");
    admin.setTenantId(0L);
    admin.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));
    service = new StatisticsSettingsService(repository, locks, admin, Clock.systemUTC());
    transaction = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    repository.deleteAll();
  }

  @Test
  void twoSavesOfTheSameRevision_Should_NotBothSucceed() throws Exception {
    var firstHasSaved = new CountDownLatch(1);
    var letFirstCommit = new CountDownLatch(1);

    Future<?> first =
        pool.submit(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      service.updateSettings(Map.of("agencyMinimumCounsellors", "2"), 0L);
                      firstHasSaved.countDown();
                      await(letFirstCommit);
                    }));
    assertThat(firstHasSaved.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Future<?> second =
        pool.submit(
            () ->
                transaction.executeWithoutResult(
                    status -> service.updateSettings(Map.of("agencyMinimumCounsellors", "3"), 0L)));
    // the second save has to wait for the first; without a lock it would be done by now
    assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    letFirstCommit.countDown();

    first.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertThatThrownBy(() -> second.get(WAIT_SECONDS, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseInstanceOf(ConflictException.class);
    assertThat(repository.findAllByOrderByIdAsc())
        .extracting(change -> change.getNewValue())
        .containsExactly("2");
  }

  @Test
  void aSecondSave_Should_Succeed_WithTheNewRevision_AfterTheFirstOneIsDone() {
    transaction.executeWithoutResult(
        status -> service.updateSettings(Map.of("agencyMinimumCounsellors", "2"), 0L));

    var saved =
        transaction.execute(
            status -> service.updateSettings(Map.of("agencyMinimumCounsellors", "3"), 1L));

    assertThat(saved.revision()).isEqualTo(2L);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
