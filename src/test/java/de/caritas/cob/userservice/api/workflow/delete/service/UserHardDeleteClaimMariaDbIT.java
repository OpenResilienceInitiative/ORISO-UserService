package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves cancellation and hard deletion cannot both win against PreDev's MariaDB semantics. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({UserHardDeleteClaimService.class, DeletionLifecycleService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserHardDeleteClaimMariaDbIT {

  private static final String USER_ID = "reactivate-hard-delete-race-proof";
  private static final String USERNAME = "race-proof@dreambau.de";

  @Autowired private UserRepository userRepository;
  @Autowired private UserHardDeleteClaimService claimService;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transactionTemplate;

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @BeforeEach
  void setUp() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    deleteProofUser();
    transactionTemplate.executeWithoutResult(ignored -> userRepository.save(readyUser()));
  }

  @AfterEach
  void tearDown() {
    deleteProofUser();
  }

  @Test
  void cancellationRowLockMakesConcurrentHardDeleteClaimLoseAfterLifecycleReset() throws Exception {
    var cancellationHasLock = new CountDownLatch(1);
    var allowCancellationCommit = new CountDownLatch(1);
    var claimStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var cancellation =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      ignored -> {
                        User locked =
                            userRepository
                                .findAllByUsernameInOrderByCreateDateAsc(List.of(USERNAME))
                                .getFirst();
                        cancellationHasLock.countDown();
                        await(allowCancellationCommit);
                        locked.setDeleteDate(null);
                        locked.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
                        locked.setDeletionReadOnlyUntil(null);
                      }));

      assertThat(cancellationHasLock.await(5, TimeUnit.SECONDS)).isTrue();
      var hardDeleteClaim =
          executor.submit(
              () -> {
                claimStarted.countDown();
                return claimService.claim(USER_ID);
              });
      assertThat(claimStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> hardDeleteClaim.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      allowCancellationCommit.countDown();
      cancellation.get(10, TimeUnit.SECONDS);
      assertThat(hardDeleteClaim.get(10, TimeUnit.SECONDS)).isEmpty();

      User persisted = userRepository.findById(USER_ID).orElseThrow();
      assertThat(persisted.getDeleteDate()).isNull();
      assertThat(persisted.getDeletionLifecycleState()).isEqualTo(DeletionLifecycleState.ACTIVE);
    } finally {
      allowCancellationCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void hardDeleteClaimMakesLaterCancellationObserveInProgressState() {
    assertThat(claimService.claim(USER_ID)).isPresent();

    transactionTemplate.executeWithoutResult(
        ignored -> {
          User locked =
              userRepository.findAllByUsernameInOrderByCreateDateAsc(List.of(USERNAME)).getFirst();
          assertThat(locked.getDeletionLifecycleState())
              .isEqualTo(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
        });
  }

  @Test
  void interruptedOrPartialHardDeleteNeverReturnsToReactivationEligibleReadOnlyState() {
    assertThat(claimService.claim(USER_ID)).isPresent();

    claimService.release(USER_ID);

    User persisted = userRepository.findById(USER_ID).orElseThrow();
    assertThat(persisted.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    assertThat(claimService.claim(USER_ID)).isPresent();
  }

  private User readyUser() {
    var user = new User(USER_ID, null, USERNAME, USERNAME, true);
    var now = LocalDateTime.now(Clock.systemUTC());
    user.setCreateDate(now);
    user.setUpdateDate(now);
    user.setTenantId(40L);
    user.setDeleteDate(now.minusDays(3));
    user.setDeletionReadOnlyUntil(now.minusMinutes(1));
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    return user;
  }

  private void deleteProofUser() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    transactionTemplate.executeWithoutResult(
        ignored -> userRepository.findById(USER_ID).ifPresent(userRepository::delete));
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for MariaDB race proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
