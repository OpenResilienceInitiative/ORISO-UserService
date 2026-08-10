package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the scheduled-task lease with the locking and constraint semantics used in PreDev.
 *
 * <p>Two concurrent callers represent two independent service replicas: both enter through the
 * proxied claim service and therefore use separate {@code REQUIRES_NEW} transactions against the
 * same MariaDB. Exactly one caller may win an initial insert or renew an expired lease.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Import({
  ScheduledTaskClaimService.class,
  ScheduledTaskClaimWriter.class,
  ScheduledTaskClaimMariaDbIT.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduledTaskClaimMariaDbIT {

  private static final String TASK_NAME = "enquiry-notification-mariadb-proof";
  private static final Duration CLAIM_DURATION = Duration.ofMinutes(30);

  @Autowired private ScheduledTaskClaimService claimService;
  @Autowired private ScheduledTaskClaimRepository claimRepository;

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
  @AfterEach
  void removeProofClaim() {
    claimRepository.deleteById(TASK_NAME);
  }

  @Test
  void twoReplicaContendersProduceOneWinnerForInitialAndExpiredClaim() throws Exception {
    assertThat(runTwoConcurrentClaims()).containsExactlyInAnyOrder(true, false);

    var firstClaim = claimRepository.findById(TASK_NAME).orElseThrow();
    var firstClaimedAt = firstClaim.getClaimedAt();
    firstClaim.setClaimedUntil(LocalDateTime.now(Clock.systemUTC()).minusSeconds(1));
    claimRepository.saveAndFlush(firstClaim);

    assertThat(runTwoConcurrentClaims()).containsExactlyInAnyOrder(true, false);
    var renewedClaim = claimRepository.findById(TASK_NAME).orElseThrow();
    assertThat(renewedClaim.getClaimedAt()).isAfter(firstClaimedAt);
    assertThat(renewedClaim.getClaimedUntil()).isAfter(renewedClaim.getClaimedAt());
  }

  private List<Boolean> runTwoConcurrentClaims() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> claimWhenReleased(ready, start));
      var second = executor.submit(() -> claimWhenReleased(ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private boolean claimWhenReleased(CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return claimService.tryClaim(TASK_NAME, CLAIM_DURATION);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent MariaDB claim proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @TestConfiguration
  static class ClockConfiguration {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
