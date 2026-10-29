package de.caritas.cob.userservice.api.service.creationoutcome;

import static de.caritas.cob.userservice.api.service.creationoutcome.CreationOperation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import de.caritas.cob.userservice.api.adapters.persistence.creation.JdbcCreationOperationStore;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.AccountCreationAttemptJournal;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/** Mandatory real-engine proof. Missing Docker fails setup; there are no environment skips. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreationOperationMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11")
          .withDatabaseName("userservice")
          .withUsername("synthetic")
          .withPassword("synthetic");

  @BeforeAll
  static void startDatabase() {
    DATABASE.start();
  }

  @AfterAll
  static void stopDatabase() {
    DATABASE.stop();
  }

  private Prepared prepared(UUID key) {
    return new Prepared(
        new Candidate(UUID.randomUUID(), key, "synthetic-owner"),
        new Scope(7, List.of(3L, 2L), "COUNSELLOR"),
        "a".repeat(64));
  }

  private AnnotationConfigApplicationContext context(MariaDBContainer<?> database) {
    var ctx = new AnnotationConfigApplicationContext();
    ctx.registerBean(
        DataSource.class,
        () -> {
          var ds = new HikariDataSource();
          ds.setJdbcUrl(database.getJdbcUrl());
          ds.setUsername(DATABASE.getUsername());
          ds.setPassword(DATABASE.getPassword());
          ds.setMaximumPoolSize(5);
          return ds;
        });
    ctx.getEnvironment().setActiveProfiles("creation-journal-mariadb-proof");
    ctx.getEnvironment()
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "saga-proof",
                java.util.Map.of(
                    "feature.appointment.enabled", "true", "multitenancy.enabled", "false")));
    ctx.register(Config.class);
    ctx.refresh();
    return ctx;
  }

  @org.springframework.context.annotation.Profile("creation-journal-mariadb-proof")
  @TestConfiguration(proxyBeanMethods = false)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Config {
    @Bean
    SpringLiquibase liquibase(DataSource ds) {
      return migration(ds);
    }

    @Bean
    @DependsOn("liquibase")
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
      var bean = new LocalContainerEntityManagerFactoryBean();
      bean.setDataSource(ds);
      bean.setPackagesToScan("de.caritas.cob.userservice.api.model");
      bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      bean.setJpaPropertyMap(
          java.util.Map.of(
              "hibernate.hbm2ddl.auto",
              "validate",
              "hibernate.dialect",
              "org.hibernate.dialect.MariaDBDialect",
              "hibernate.jdbc.time_zone",
              "UTC",
              "hibernate.physical_naming_strategy",
              "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
      return bean;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource ds, EntityManagerFactory emf) {
      var manager = new JpaTransactionManager(emf);
      manager.setDataSource(ds);
      manager.setJpaDialect(new HibernateJpaDialect());
      return manager;
    }

    @Bean
    SagaFixture sagaFixture(EntityManagerFactory emf, DataSource ds) {
      return new SagaFixture(emf, ds);
    }

    @Bean
    CreationOperationAccess creationAccess(SagaFixture fixture) {
      return fixture.dependency(CreationOperationAccess.class);
    }

    @Bean
    de.caritas.cob.userservice.api.admin.service.admin.AdminScope lifecycleAdminScope(
        SagaFixture fixture) {
      return fixture.dependency(
          de.caritas.cob.userservice.api.admin.service.admin.AdminScope.class);
    }

    @Bean
    CreateConsultantSaga saga(
        SagaFixture fixture, AccountCreationAttemptJournal journal, CreationOperationWriter writer)
        throws Exception {
      return fixture.create(journal, writer);
    }

    @Bean
    JdbcCreationOperationStore repository(DataSource ds) {
      return spy(new JdbcCreationOperationStore(new JdbcTemplate(ds)));
    }

    @Bean
    CreationOperationWriter writer(JdbcCreationOperationStore repo) {
      return new CreationOperationWriter(repo);
    }

    @Bean
    AccountCreationAttemptJournal journal(CreationOperationWriter writer) {
      return new AccountCreationAttemptJournal(
          mock(AccountCreationAttemptRepository.class), java.time.Clock.systemUTC(), writer);
    }
  }

  static class SagaFixture {
    final EntityManagerFactory emf;
    final DataSource ds;
    final String nativeId = UUID.randomUUID().toString();
    final java.util.Map<Class<?>, Object> dependencies = new java.util.HashMap<>();
    boolean failAfterRow;
    boolean failAtCommit;
    boolean sawEnlistedConnection;

    SagaFixture(EntityManagerFactory emf, DataSource ds) {
      this.emf = emf;
      this.ds = ds;
    }

    @SuppressWarnings("unchecked")
    <T> T dependency(Class<T> type) {
      return (T) dependencies.computeIfAbsent(type, key -> mock(type));
    }

    CreateConsultantSaga create(
        AccountCreationAttemptJournal journal, CreationOperationWriter writer) throws Exception {
      var constructor = CreateConsultantSaga.class.getConstructors()[0];
      var arguments =
          java.util.Arrays.stream(constructor.getParameterTypes())
              .map(
                  type ->
                      type == AccountCreationAttemptJournal.class
                          ? journal
                          : type == CreationOperationWriter.class ? writer : dependency(type))
              .toArray();
      var saga = (CreateConsultantSaga) constructor.newInstance(arguments);
      org.springframework.test.util.ReflectionTestUtils.setField(saga, "creationJournal", journal);
      org.springframework.test.util.ReflectionTestUtils.setField(saga, "creationWriter", writer);
      org.springframework.test.util.ReflectionTestUtils.setField(
          saga, "creationAccess", dependency(CreationOperationAccess.class));
      org.springframework.test.util.ReflectionTestUtils.setField(
          saga, "multiTenancyEnabled", false);
      org.springframework.test.util.ReflectionTestUtils.setField(
          saga, "appointmentFeatureEnabled", true);
      when(dependency(
                  de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.class)
              .forNewConsultant(any()))
          .thenReturn(
              new de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService
                  .RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
      when(dependency(de.caritas.cob.userservice.api.port.out.IdentityClient.class)
              .createUser(any(), any(), any(), any()))
          .thenAnswer(
              call -> {
                var created = new CreatedIdentity(nativeId);
                ((de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture)
                        call.getArgument(3))
                    .captured(created);
                return created;
              });
      var entityManager =
          org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf);
      when(dependency(de.caritas.cob.userservice.api.service.ConsultantService.class)
              .saveConsultant(any()))
          .thenAnswer(
              call -> {
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .hasResource(ds))
                    .isTrue();
                assertThat(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .hasResource(emf))
                    .isTrue();
                sawEnlistedConnection = true;
                de.caritas.cob.userservice.api.model.Consultant consultant = call.getArgument(0);
                entityManager.persist(consultant);
                if (failAtCommit)
                  consultant.setFirstName(
                      null); // Hibernate validates this during the actual proxy commit.
                return consultant;
              });
      doAnswer(
              call -> {
                if (failAfterRow) throw new IllegalStateException("synthetic downstream failure");
                return null;
              })
          .when(
              dependency(
                  de.caritas.cob.userservice.api.service.appointment.AppointmentService.class))
          .createConsultant(any(), eq(true));
      return saga;
    }
  }

  private CreateConsultantDTO sagaInput() {
    return new CreateConsultantDTO()
        .username("synthetic" + UUID.randomUUID().toString().replace("-", ""))
        .email("synthetic@example.invalid")
        .password("SyntheticPassword1!")
        .firstname("Synthetic")
        .lastname("Receipt")
        .tenantId(7L)
        .isGroupchatConsultant(false);
  }

  @Test
  void actualProxiedSagaJpaCommitIsObservedThroughSharedDataSource() {
    try (var ctx = context(DATABASE)) {
      var manager = ctx.getBean(PlatformTransactionManager.class);
      assertThat(manager).isInstanceOf(JpaTransactionManager.class);
      assertThat(((JpaTransactionManager) manager).getDataSource())
          .isSameAs(ctx.getBean(DataSource.class));
      assertThat(((JpaTransactionManager) manager).getJpaDialect())
          .isInstanceOf(HibernateJpaDialect.class);
      var saga = ctx.getBean(CreateConsultantSaga.class);
      assertThat(org.springframework.aop.support.AopUtils.isAopProxy(saga)).isTrue();
      var invocation =
          new CreationOperationContext(
              new Candidate(UUID.randomUUID(), UUID.randomUUID(), "synthetic-owner"));
      saga.createNewConsultant(sagaInput(), invocation);
      var receipt =
          ctx.getBean(AccountCreationAttemptJournal.class)
              .findById(invocation.receipt().operationId())
              .orElseThrow();
      assertThat(receipt.row()).isEqualTo(RowState.COMMITTED);
      assertThat(receipt.nativeId()).isEqualTo(ctx.getBean(SagaFixture.class).nativeId);
      assertThat(ctx.getBean(SagaFixture.class).sawEnlistedConnection).isTrue();
      assertThat(
              new JdbcTemplate(ctx.getBean(DataSource.class))
                  .queryForObject(
                      "SELECT COUNT(*) FROM consultant WHERE consultant_id=?",
                      Integer.class,
                      receipt.nativeId()))
          .isEqualTo(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void actualOuterSagaRollbackAndCommitFailureRetainIndependentJournal(boolean commitFailure) {
    try (var ctx = context(DATABASE)) {
      var fixture = ctx.getBean(SagaFixture.class);
      fixture.failAtCommit = commitFailure;
      fixture.failAfterRow = !commitFailure;
      var invocation =
          new CreationOperationContext(
              new Candidate(UUID.randomUUID(), UUID.randomUUID(), "synthetic-owner"));
      assertThatThrownBy(
              () ->
                  ctx.getBean(CreateConsultantSaga.class)
                      .createNewConsultant(sagaInput(), invocation))
          .isInstanceOf(RuntimeException.class);
      var receipt =
          ctx.getBean(AccountCreationAttemptJournal.class)
              .findByKey(invocation.candidate().operationKey())
              .orElseThrow();
      assertThat(fixture.sawEnlistedConnection).isTrue();
      assertThat(receipt.nativeId()).isEqualTo(fixture.nativeId);
      assertThat(receipt.dispatch()).isEqualTo(DispatchState.NATIVE_ID_CAPTURED);
      assertThat(receipt.row()).isEqualTo(RowState.ROLLED_BACK);
      assertThat(
              new JdbcTemplate(ctx.getBean(DataSource.class))
                  .queryForObject(
                      "SELECT COUNT(*) FROM consultant WHERE consultant_id=?",
                      Integer.class,
                      fixture.nativeId))
          .isZero();
    }
  }

  @Test
  void requiresNewWriterFailureRollsBackItsJdbcConditionalWriteAndNeverPermitsDispatch() {
    try (var ctx = context(DATABASE)) {
      var repo = ctx.getBean(JdbcCreationOperationStore.class);
      var journal = ctx.getBean(AccountCreationAttemptJournal.class);
      var p = prepared(UUID.randomUUID());
      var claim = journal.claim(p);
      var invocation = new CreationOperationContext(p.candidate());
      invocation.bind(claim.receipt());
      doAnswer(
              call -> {
                call.callRealMethod();
                throw new IllegalStateException("synthetic failure after conditional JDBC write");
              })
          .when(repo)
          .compareAndSet(any(), anyLong());
      var posts = new AtomicInteger();
      assertThatThrownBy(
              () -> {
                journal.beginDispatch(invocation, claim);
                posts.incrementAndGet();
              })
          .isInstanceOf(RuntimeException.class);
      assertThat(posts).hasValue(0);
      var receipt = journal.findById(claim.receipt().operationId()).orElseThrow();
      assertThat(receipt.dispatch()).isEqualTo(DispatchState.PREPARED);
      assertThat(receipt.version()).isZero();
      doAnswer(
              call -> {
                call.callRealMethod();
                throw new IllegalStateException("synthetic failure after insert");
              })
          .when(repo)
          .insert(any());
      var failed = prepared(UUID.randomUUID());
      assertThatThrownBy(() -> ctx.getBean(CreationOperationWriter.class).prepare(failed))
          .isInstanceOf(RuntimeException.class);
      assertThat(journal.findByKey(failed.candidate().operationKey())).isEmpty();
    }
  }

  private static SpringLiquibase migration(DataSource ds) {
    var migration = new SpringLiquibase();
    migration.setDataSource(ds);
    migration.setChangeLog("classpath:db/changelog/userservice-master.xml");
    migration.setContexts("dev,seed");
    return migration;
  }

  @Test
  void twoApplicationContextsPermitExactlyOneNativePostAndReturnThePersistedWinner()
      throws Exception {
    try (var first = context(DATABASE);
        var second = context(DATABASE)) {
      var key = UUID.randomUUID();
      var barrier = new CyclicBarrier(2);
      var posts = new AtomicInteger();
      Callable<Receipt> a = () -> contender(first, prepared(key), barrier, posts);
      Callable<Receipt> b = () -> contender(second, prepared(key), barrier, posts);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var f1 = executor.submit(a);
        var f2 = executor.submit(b);
        var r1 = f1.get(30, TimeUnit.SECONDS);
        var r2 = f2.get(30, TimeUnit.SECONDS);
        assertThat(r1.operationId()).isEqualTo(r2.operationId());
        assertThat(posts).hasValue(1);
        assertThat(
                first
                    .getBean(AccountCreationAttemptJournal.class)
                    .findByKey(key)
                    .orElseThrow()
                    .nativeId())
            .isEqualTo("native-contender");
      }
    }
  }

  private Receipt contender(
      AnnotationConfigApplicationContext ctx,
      Prepared p,
      CyclicBarrier barrier,
      AtomicInteger posts)
      throws Exception {
    barrier.await(15, TimeUnit.SECONDS);
    var journal = ctx.getBean(AccountCreationAttemptJournal.class);
    var claim = journal.claim(p);
    var invocation = new CreationOperationContext(p.candidate());
    invocation.bind(claim.receipt());
    if (claim.newlyInserted()) {
      var capture = journal.beginDispatch(invocation, claim);
      posts.incrementAndGet();
      capture.captured(new CreatedIdentity("native-contender"));
    } else
      assertThatThrownBy(() -> journal.beginDispatch(invocation, claim))
          .isInstanceOf(IllegalStateException.class);
    return invocation.receipt();
  }

  @Test
  void independentWritesSurviveOuterRollbackPoolRestartAndAccountDeletion() {
    UUID operation;
    UUID key = UUID.randomUUID();
    try (var ctx = context(DATABASE)) {
      var journal = ctx.getBean(AccountCreationAttemptJournal.class);
      var p = prepared(key);
      operation = p.candidate().operationId();
      var outer = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
      outer.executeWithoutResult(
          status -> {
            var claim = journal.claim(p);
            var invocation = new CreationOperationContext(p.candidate());
            invocation.bind(claim.receipt());
            var capture = journal.beginDispatch(invocation, claim);
            capture.captured(new CreatedIdentity("native-rollback"));
            status.setRollbackOnly();
          });
      assertThat(journal.findById(operation).orElseThrow().nativeId()).isEqualTo("native-rollback");
      var jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
      jdbc.update(
          "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id) VALUES ('native-rollback', 'synthetic-rollback', 'Synthetic', 'Receipt', 'receipt@example.invalid', 7)");
      jdbc.update("DELETE FROM consultant WHERE consultant_id = 'native-rollback'");
      assertThat(journal.findByKey(key).orElseThrow().nativeId()).isEqualTo("native-rollback");
    }
    try (var restarted = context(DATABASE)) {
      var receipt =
          restarted.getBean(AccountCreationAttemptJournal.class).findById(operation).orElseThrow();
      assertThat(receipt.nativeId()).isEqualTo("native-rollback");
      assertThat(receipt.dispatch()).isEqualTo(DispatchState.NATIVE_ID_CAPTURED);
    }
  }

  @Test
  void staleTransitionAndCaptureRacePreservesEveryDimension() throws Exception {
    try (var first = context(DATABASE);
        var second = context(DATABASE)) {
      var journal = first.getBean(AccountCreationAttemptJournal.class);
      var p = prepared(UUID.randomUUID());
      var claim = journal.claim(p);
      var invocation = new CreationOperationContext(p.candidate());
      invocation.bind(claim.receipt());
      var capture = journal.beginDispatch(invocation, claim);
      var barrier = new CyclicBarrier(2);
      long stale = invocation.receipt().version();
      try (var executor = Executors.newFixedThreadPool(2)) {
        var a =
            executor.submit(
                () -> {
                  barrier.await();
                  capture.captured(new CreatedIdentity("native-race"));
                  return true;
                });
        var b =
            executor.submit(
                () -> {
                  barrier.await();
                  return second
                      .getBean(CreationOperationWriter.class)
                      .transition(
                          p.candidate().operationId(),
                          stale,
                          new CreationTransition.Workflow(WorkflowState.IN_PROGRESS, Reason.NONE));
                });
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
      }
      var persisted = journal.findById(p.candidate().operationId()).orElseThrow();
      assertThat(persisted.nativeId()).isEqualTo("native-race");
      assertThat(persisted.workflow()).isEqualTo(WorkflowState.IN_PROGRESS);
      assertThat(persisted.version()).isEqualTo(3);
    }
  }

  @Test
  void additiveUpgradeRetainsExistingAccountFromPreviousChangelogBoundary() throws Exception {
    UUID historicalKey = UUID.randomUUID();
    try (var upgrade =
        new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("userservice")
            .withUsername("synthetic")
            .withPassword("synthetic")) {
      upgrade.start();
      try (var ds = new HikariDataSource()) {
        ds.setJdbcUrl(upgrade.getJdbcUrl());
        ds.setUsername(upgrade.getUsername());
        ds.setPassword(upgrade.getPassword());
        var previousMaster = java.nio.file.Files.createTempFile("creation-previous-master", ".xml");
        try (var input = getClass().getResourceAsStream("/db/changelog/userservice-master.xml")) {
          String master = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          master =
              master.replace(
                  "  <include file=\"db/changelog/changeset/20261029_account_creation_receipt/changeSet.xml\"/>\n",
                  "");
          java.nio.file.Files.writeString(previousMaster, master);
        }
        var previous = migration(ds);
        previous.setChangeLog(previousMaster.toUri().toString());
        try {
          previous.afterPropertiesSet();
        } finally {
          java.nio.file.Files.delete(previousMaster);
        }
        var jdbc = new JdbcTemplate(ds);
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'userservice' AND table_name = 'account_creation_attempt' AND column_name = 'receipt_id'",
                    Integer.class))
            .isZero();
        jdbc.update(
            "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id) VALUES ('synthetic-upgrade', 'synthetic-upgrade', 'Before', 'Upgrade', 'upgrade@example.invalid', 7)");
        var before =
            jdbc.queryForMap("SELECT * FROM consultant WHERE consultant_id = 'synthetic-upgrade'");
        jdbc.update(
            "INSERT INTO account_creation_attempt(operation_key,owner_id,tenant_id,native_identity_id,status,created_at,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            historicalKey.toString(),
            "retained-owner",
            7L,
            "retained-native",
            "NATIVE_ID_KNOWN");
        var beforeAttempt =
            jdbc.queryForMap(
                "SELECT * FROM account_creation_attempt WHERE operation_key=?",
                historicalKey.toString());
        migration(ds).afterPropertiesSet();
        var afterAttempt =
            jdbc.queryForMap(
                "SELECT * FROM account_creation_attempt WHERE operation_key=?",
                historicalKey.toString());
        assertThat(afterAttempt.entrySet()).containsAll(beforeAttempt.entrySet());
        assertThat(afterAttempt.get("receipt_id")).isNull();
        assertThat(afterAttempt.get("dispatch")).isNull();
        assertThat(afterAttempt.get("receipt_version")).isNull();
        assertThat(
                jdbc.queryForMap(
                    "SELECT * FROM consultant WHERE consultant_id = 'synthetic-upgrade'"))
            .isEqualTo(before);
      }
      try (var upgraded = context(upgrade)) {
        var journal = upgraded.getBean(AccountCreationAttemptJournal.class);
        assertThat(journal.findByKey(historicalKey)).isEmpty();
        assertThatThrownBy(() -> journal.claim(prepared(historicalKey)))
            .isInstanceOf(IllegalStateException.class);
        var claim = journal.claim(prepared(UUID.randomUUID()));
        assertThat(claim.newlyInserted()).isTrue();
        assertThat(claim.receipt().dispatch()).isEqualTo(DispatchState.PREPARED);
      }
    }
  }
}
