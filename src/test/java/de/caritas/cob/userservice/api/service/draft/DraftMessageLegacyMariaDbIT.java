package de.caritas.cob.userservice.api.service.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.port.out.DraftMessageRepository;
import de.caritas.cob.userservice.api.service.draft.DraftMessageService.UpsertDraftRequest;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Two independent service instances share only the MariaDB writer's legacy draft lock. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DraftMessageLegacyMariaDbIT {

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
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
    registry.add("multitenancy.enabled", () -> "false");
  }

  @Autowired private DraftMessageRepository repository;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void twoFirstAutosavesShareOneRowAndHoldTheirWriteConnectionLockThroughCommit() throws Exception {
    String owner = UUID.randomUUID().toString();
    String scope = "legacy:concurrent-autosave";
    var bothSawAnEmptyScope = new CountDownLatch(2);
    var first = replica(owner, scope, bothSawAnEmptyScope);
    var second = replica(owner, scope, bothSawAnEmptyScope);

    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> saveWithCommitProbe(first, owner, scope, "First autosave"));
      var b = workers.submit(() -> saveWithCommitProbe(second, owner, scope, "Second autosave"));
      a.get(15, TimeUnit.SECONDS);
      b.get(15, TimeUnit.SECONDS);

      assertThat(
              jdbc.queryForList(
                  "SELECT text FROM draft_message WHERE user_id = ? AND scope_key = ?",
                  String.class,
                  owner,
                  scope))
          .hasSize(1)
          .allMatch(text -> text.equals("First autosave") || text.equals("Second autosave"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT IS_FREE_LOCK(?)", Integer.class, LegacyDraftWriteLock.lockName(owner)))
          .isEqualTo(1);
    } finally {
      repository.deleteByUserId(owner);
    }
  }

  private DraftMessageService replica(String owner, String scope, CountDownLatch missingLookups) {
    var reads = mock(DraftMessageRepository.class, delegatesTo(repository));
    var firstLookup = new AtomicBoolean(true);
    doAnswer(
            call -> {
              var result =
                  repository
                      .findFirstForUpdateByUserIdAndScopeKeyAndProtectedScopeKeyIsNullOrderByUpdateDateDescIdDesc(
                          owner, scope);
              if (firstLookup.getAndSet(false)) {
                assertThat(result).isEmpty();
                missingLookups.countDown();
                assertThat(missingLookups.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(reads)
        .findFirstForUpdateByUserIdAndScopeKeyAndProtectedScopeKeyIsNullOrderByUpdateDateDescIdDesc(
            owner, scope);
    return new DraftMessageService(reads, new LegacyDraftWriteLock(entityManager));
  }

  private void saveWithCommitProbe(
      DraftMessageService service, String owner, String scope, String text) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        ignored -> {
          var writeConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT IS_USED_LOCK(?)",
                              Long.class,
                              LegacyDraftWriteLock.lockName(owner)))
                      .isEqualTo(writeConnection);
                }
              });
          var request = new UpsertDraftRequest();
          request.setText(text);
          service.upsertDraft(owner, scope, request, 1L);
        });
  }
}
