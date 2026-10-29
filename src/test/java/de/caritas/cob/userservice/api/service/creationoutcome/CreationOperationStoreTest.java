package de.caritas.cob.userservice.api.service.creationoutcome;

import static de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.persistence.creation.JdbcCreationOperationStore;
import de.caritas.cob.userservice.api.admin.service.consultant.create.AccountCreationAttemptJournal;
import de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Physical H2/JDBC contract; the separate retained JPA8 remains mandatory on real MariaDB. */
class CreationOperationStoreTest {
  @Configuration(proxyBeanMethods = false)
  @Profile("creation-operation-store-test")
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Config {
    @Bean
    JdbcTemplate jdbc(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    JdbcCreationOperationStore store(JdbcTemplate jdbc) {
      return new JdbcCreationOperationStore(jdbc);
    }

    @Bean
    CreationOperationWriter writer(JdbcCreationOperationStore store) {
      return new CreationOperationWriter(store);
    }

    @Bean
    AccountCreationAttemptJournal journal(CreationOperationWriter writer) {
      return new AccountCreationAttemptJournal(
          mock(AccountCreationAttemptRepository.class), Clock.systemUTC(), writer);
    }
  }

  private AnnotationConfigApplicationContext context(String database, boolean migrate)
      throws Exception {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("creation-operation-store-test");
    var dataSource = new DriverManagerDataSource(database, "sa", "");
    if (migrate) {
      var liquibase = new SpringLiquibase();
      liquibase.setDataSource(dataSource);
      liquibase.setChangeLog("classpath:creation-outcome-store-test.xml");
      liquibase.afterPropertiesSet();
    }
    context.registerBean(DataSource.class, () -> dataSource);
    context.register(Config.class);
    context.refresh();
    return context;
  }

  private String database() {
    return "jdbc:h2:mem:creation-"
        + UUID.randomUUID()
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  }

  private Prepared prepared(UUID key) {
    return new Prepared(
        new Candidate(UUID.randomUUID(), key, "synthetic-owner"),
        new Scope(7L, List.of(3L, 2L), "COUNSELLOR"),
        "a".repeat(64));
  }

  @Test
  void anExistingFoundationRowNeverGainsATrackedDispatchPermit() throws Exception {
    try (var context = context(database(), true)) {
      var jdbc = context.getBean(JdbcTemplate.class);
      UUID key = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO account_creation_attempt(operation_key,owner_id,tenant_id,native_identity_id,status,created_at,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          key.toString(),
          "synthetic-owner",
          7L,
          "retained-native",
          "NATIVE_ID_KNOWN");
      var before =
          jdbc.queryForMap(
              "SELECT * FROM account_creation_attempt WHERE operation_key=?", key.toString());
      var journal = context.getBean(AccountCreationAttemptJournal.class);
      assertTrue(journal.findByKey(key).isEmpty());
      var posts = new AtomicInteger();
      assertThrows(
          IllegalStateException.class,
          () -> {
            journal.claim(prepared(key));
            posts.incrementAndGet();
          });
      assertEquals(0, posts.get());
      assertEquals(
          before,
          jdbc.queryForMap(
              "SELECT * FROM account_creation_attempt WHERE operation_key=?", key.toString()));
      assertNull(before.get("receipt_id"));
      assertNull(before.get("dispatch"));
    }
  }

  @Test
  void twoContextsShareOneDurableDispatchAndOneNativeIdentity() throws Exception {
    String database = database();
    try (var first = context(database, true);
        var second = context(database, false)) {
      UUID key = UUID.randomUUID();
      var barrier = new CyclicBarrier(2);
      var posts = new AtomicInteger();
      try (var executor = Executors.newFixedThreadPool(2)) {
        var a = executor.submit(() -> contend(first, key, barrier, posts));
        var b = executor.submit(() -> contend(second, key, barrier, posts));
        UUID winner = a.get(15, TimeUnit.SECONDS);
        assertEquals(winner, b.get(15, TimeUnit.SECONDS));
        assertEquals(1, posts.get());
        var result =
            first.getBean(AccountCreationAttemptJournal.class).findById(winner).orElseThrow();
        assertEquals("actual-captured-native", result.nativeId());
        assertEquals(DispatchState.NATIVE_ID_CAPTURED, result.dispatch());
        assertEquals(
            1,
            first
                .getBean(JdbcTemplate.class)
                .queryForObject("SELECT COUNT(*) FROM account_creation_attempt", Integer.class));
      }
    }
  }

  private UUID contend(
      AnnotationConfigApplicationContext context,
      UUID key,
      CyclicBarrier barrier,
      AtomicInteger posts)
      throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    var journal = context.getBean(AccountCreationAttemptJournal.class);
    var prepared = prepared(key);
    var claim = journal.claim(prepared);
    var invocation = new CreationOperationContext(prepared.candidate());
    invocation.bind(claim.receipt());
    if (claim.newlyInserted()) {
      var capture = journal.beginDispatch(invocation, claim);
      posts.incrementAndGet();
      capture.captured(new CreatedIdentity("actual-captured-native"));
    } else
      assertThrows(IllegalStateException.class, () -> journal.beginDispatch(invocation, claim));
    return invocation.receipt().operationId();
  }

  @Test
  void capturedIdentityAndIndependentDimensionsSurviveAStaleTransition() throws Exception {
    try (var context = context(database(), true)) {
      var journal = context.getBean(AccountCreationAttemptJournal.class);
      var p = prepared(UUID.randomUUID());
      var claim = journal.claim(p);
      var invocation = new CreationOperationContext(p.candidate());
      invocation.bind(claim.receipt());
      var capture = journal.beginDispatch(invocation, claim);
      long stale = invocation.receipt().version();
      capture.captured(new CreatedIdentity("captured-once"));
      var updated =
          context
              .getBean(CreationOperationWriter.class)
              .transition(
                  invocation.receipt().operationId(),
                  stale,
                  new CreationTransition.Workflow(WorkflowState.IN_PROGRESS, Reason.NONE));
      assertEquals("captured-once", updated.nativeId());
      assertEquals(DispatchState.NATIVE_ID_CAPTURED, updated.dispatch());
      assertEquals(WorkflowState.IN_PROGRESS, updated.workflow());
      assertEquals(3, updated.version());
      assertThrows(
          CreationOperationFailure.class,
          () ->
              context
                  .getBean(CreationOperationWriter.class)
                  .capture(updated.operationId(), updated.version(), "unrelated-native"));
      var row =
          context
              .getBean(JdbcTemplate.class)
              .queryForMap(
                  "SELECT * FROM account_creation_attempt WHERE receipt_id=?",
                  updated.operationId().toString());
      assertEquals("captured-once", row.get("native_identity_id"));
      assertEquals("NATIVE_ID_KNOWN", row.get("status"));
    }
  }
}
