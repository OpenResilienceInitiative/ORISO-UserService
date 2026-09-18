package de.caritas.cob.userservice.api.service.guestjoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.GuestJoinTarget;
import de.caritas.cob.userservice.api.port.out.GuestJoinAttemptRepository;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.sql.init.mode=never")
@ActiveProfiles("testing")
@Import(GuestJoinAttemptStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuestJoinAttemptStoreTest {
  private static final GuestJoinCapability CAPABILITY =
      GuestJoinCapability.parse(
          Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
  private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 10, 1, 12, 0);

  @Autowired GuestJoinAttemptStore store;
  @Autowired GuestJoinAttemptRepository repository;
  @Autowired PlatformTransactionManager transactions;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void preparationCommitsBeforeTheCallingTransactionCanFail() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            transaction -> {
              store.prepare(
                  CAPABILITY,
                  new GuestJoinTarget(42L, 7L, 9L, 1),
                  "biene_rayan_1234",
                  "bee.svg",
                  true,
                  EXPIRES);
              transaction.setRollbackOnly();
            });

    var persisted = repository.findByKeyHash(CAPABILITY.attemptHash()).orElseThrow();
    assertThat(persisted.getOriginalUsername()).isEqualTo("biene_rayan_1234");
    assertThat(persisted.getPhase().name()).isEqualTo("PREPARED");
    assertThat(persisted.getExpiresAt()).isEqualTo(EXPIRES);
  }

  @Test
  void retryCannotRebindTheInvitationOrSelectedIdentity() {
    var first =
        store.prepare(
            CAPABILITY,
            new GuestJoinTarget(42L, 7L, 9L, 1),
            "biene_rayan_1234",
            "bee.svg",
            true,
            EXPIRES);
    assertThat(
            store
                .prepare(
                    CAPABILITY,
                    new GuestJoinTarget(42L, 7L, 9L, 1),
                    "biene_rayan_1234",
                    "bee.svg",
                    true,
                    EXPIRES.plusDays(1))
                .getId())
        .isEqualTo(first.getId());
    assertThatThrownBy(
            () ->
                store.prepare(
                    CAPABILITY,
                    new GuestJoinTarget(43L, 7L, 9L, 1),
                    "biene_rayan_1234",
                    "bee.svg",
                    true,
                    EXPIRES))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                store.prepare(
                    CAPABILITY,
                    new GuestJoinTarget(42L, 7L, 9L, 1),
                    "biene_rayan_1235",
                    "bee.svg",
                    true,
                    EXPIRES))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                store.prepare(
                    CAPABILITY,
                    new GuestJoinTarget(42L, 7L, 9L, 1),
                    "biene_rayan_1234",
                    "cat.svg",
                    true,
                    EXPIRES))
        .isInstanceOf(ConflictException.class);
    assertThat(repository.count()).isEqualTo(1);
    assertThat(repository.findById(first.getId()).orElseThrow().getExpiresAt()).isEqualTo(EXPIRES);
  }

  @Test
  void simultaneousRequestsConvergeOnOneCommittedAttempt() throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Long> request =
          () -> {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return store
                .prepare(
                    CAPABILITY,
                    new GuestJoinTarget(42L, 7L, 9L, 1),
                    "biene_rayan_1234",
                    "bee.svg",
                    true,
                    EXPIRES)
                .getId();
          };
      var first = executor.submit(request);
      var second = executor.submit(request);
      start.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
    }
    assertThat(repository.count()).isEqualTo(1);
  }
}
