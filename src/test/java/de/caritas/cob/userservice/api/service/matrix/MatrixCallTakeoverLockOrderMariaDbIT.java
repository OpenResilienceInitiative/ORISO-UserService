package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.CallRoomOrigin;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixCallBindingRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * FE#2209 round 3 on MariaDB: one global lock order for session calls (binding, then session, then
 * observation and end rows, then link). The takeover of an asker's binding is paused while it holds
 * its locks; an explicit end and the lifecycle's handleRoom run meanwhile. With the old order
 * (takeover: session first, binding later; end and handleRoom: binding first) InnoDB detects a
 * deadlock. With one order, the other transaction just waits.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  SessionCallAuthority.class,
  SessionCallRevival.class,
  MatrixCallObservationService.class,
  de.caritas.cob.userservice.api.service.matrix.DedicatedCallRoom.class,
  MatrixSessionCallBindingService.class,
  MatrixCallBindingService.class,
  MatrixCallBindingWriter.class,
  MatrixCallConversationResolver.class,
  MatrixCallLifecycleService.class,
  MatrixCallGuestService.class,
  OrisoRoomRegistry.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatrixCallTakeoverLockOrderMariaDbIT {
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>(System.getProperty("it.mariadb.image", "mariadb:10.11"))
          .withDatabaseName("userservice");

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
    // Deployment isolation level: nothing in the application, Hikari or the MariaDB chart sets one,
    // so production runs MariaDB's default REPEATABLE READ. Pinned so the race ITs prove that.
    registry.add(
        "spring.datasource.hikari.transaction-isolation", () -> "TRANSACTION_REPEATABLE_READ");
    registry.add(
        "spring.datasource.hikari.connection-init-sql",
        () -> "SET SESSION innodb_lock_wait_timeout = 15");
  }

  @Autowired private MatrixCallObservationService observations;
  @Autowired private MatrixSessionCallBindingService sessionBindings;
  @Autowired private MatrixCallLifecycleService lifecycle;
  @Autowired private MatrixCallBindingRepository bindings;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private SessionRepository sessions;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoBean
  private de.caritas.cob.userservice.api.service.lifecycle.SessionInteractionService
      sessionInteractions;

  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private EventNotificationService notifications;
  @MockitoBean private AccountInactivityService accountLifecycle;
  @MockitoSpyBean private MatrixCallGuestService callGuests;
  @MockitoSpyBean private MatrixCallConversationResolver conversations;

  private String source;
  private String askerMedia;
  private String counsellorMedia;
  private String askerMatrixId;
  private Consultant counsellor;
  private AuthenticatedUser actor;

  @BeforeEach
  void setUp() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    askerMatrixId = "@asker-" + suffix + ":example";
    source = "!order-source-" + suffix + ":example";
    askerMedia = "!order-asker-media-" + suffix + ":example";
    counsellorMedia = "!order-counsellor-media-" + suffix + ":example";
    var transaction = new TransactionTemplate(transactionManager);
    Long sessionId =
        transaction.execute(
            status -> {
              var asker = users.save(user("asker-" + suffix, askerMatrixId));
              counsellor = consultants.save(consultant());
              var session =
                  new Session(asker, 1, "12345", 1L, Session.SessionStatus.IN_PROGRESS, false);
              session.setTenantId(7L);
              session.setMatrixRoomId(source);
              session.setLanguageCode(LanguageCode.de);
              session.setIsConsultantDirectlySet(false);
              session.setConsultant(counsellor);
              session.setCreateDate(LocalDateTime.now());
              session.setUpdateDate(LocalDateTime.now());
              session.setSupervisionOptedOut(false);
              return sessions.save(session).getId();
            });
    long now = System.currentTimeMillis();
    // The asker's plaintext invite: an authority-less binding of the same call.
    transaction.executeWithoutResult(
        status ->
            bindings.save(
                MatrixCallBinding.builder()
                    .sourceRoomId(source)
                    .callId("call")
                    .mediaRoomId(askerMedia)
                    .callerMatrixId(askerMatrixId)
                    .sessionId(sessionId)
                    .tenantId(7L)
                    .invitedAt(now)
                    .inviteExpiresAt(now + 60_000)
                    .video(true)
                    .build()));
    actor = new AuthenticatedUser();
    actor.setUserId(counsellor.getId());
    actor.setTenantId(7L);
    actor.setRoles(Set.of("consultant"));
    String caller = counsellor.getMatrixUserId();
    when(accountLifecycle.snapshot(anyString())).thenReturn(Optional.empty());
    when(matrix.getCallRoomMembers(source)).thenReturn(Optional.of(List.of(caller, askerMatrixId)));
    when(matrix.getCallRoomBinding(counsellorMedia, caller))
        .thenReturn(Optional.of(Map.of("call_id", "call", "source_room_id", source)));
    when(matrix.getCallRoomOrigin(counsellorMedia, caller))
        .thenReturn(Optional.of(new CallRoomOrigin(caller, caller)));
    when(matrix.ensureAdminInRoom(anyString(), anyString())).thenReturn(true);
  }

  @Test
  void anEndDuringTheTakeOverWaitsInsteadOfDeadlocking() throws Exception {
    var other = runWhileTheTakeOverHoldsItsLocks(() -> observations.end(actor, source, "call"));
    assertThat(other).isEqualTo(MatrixCallObservationService.Outcome.OK);
    // The end came second: it ends the counsellor's call that the takeover committed.
    var binding = bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow();
    assertThat(binding.getMediaRoomId()).isEqualTo(counsellorMedia);
    assertThat(binding.getEndedAt()).isNotNull();
  }

  @Test
  void theLifecycleDuringTheTakeOverWaitsInsteadOfDeadlocking() throws Exception {
    var other = runWhileTheTakeOverHoldsItsLocks(() -> lifecycle.handleRoom(askerMedia, Map.of()));
    // After the takeover the asker's call room is bound to nothing any more.
    assertThat(other).isEqualTo(false);
    var binding = bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow();
    assertThat(binding.getMediaRoomId()).isEqualTo(counsellorMedia);
    assertThat(binding.getEndedAt()).isNull();
  }

  @Test
  void aBindingThatStopsBeingRevivableBetweenTheGateAndTheLockIsNotTakenOver() throws Exception {
    // Round 6 (F4): the server closed the asker's binding unseen, so bind's gate (outside any
    // transaction) finds it revivable. Before bind's transaction locks it, the counsellor's own
    // explicit end commits. The checks under the lock see that fresh state and refuse.
    serverClosedTheAskersBinding();
    boolean bound =
        bindWithACommitBetweenTheGateAndTheLock(
            () -> {
              // Commits completely while bind waits: bind holds no lock yet.
              assertThat(observations.end(actor, source, "call"))
                  .isEqualTo(MatrixCallObservationService.Outcome.OK);
              assertThat(endRows(counsellor.getMatrixUserId())).isEqualTo(1);
            });
    assertThat(bound).isFalse();
    var binding = bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow();
    assertThat(binding.getEndedAt()).isNotNull();
    assertThat(binding.getEndCause()).isEqualTo(MatrixCallBinding.END_CAUSE_LIFECYCLE);
    assertThat(binding.getCallerMatrixId()).isEqualTo(askerMatrixId);
    assertThat(binding.getMediaRoomId()).isEqualTo(askerMedia);
  }

  @Test
  void aBindingWhoseEndCauseTurnsToEndpointBetweenTheGateAndTheLockIsNotTakenOver()
      throws Exception {
    // CodeRabbit 4573425474: only the binding's own state changes between the gate and the lock,
    // end_cause LIFECYCLE -> ENDPOINT, and no observation end is written. Only the in-lock
    // revival.revivableBy re-check can see that; the end-row check under the lock finds nothing.
    serverClosedTheAskersBinding();
    boolean bound =
        bindWithACommitBetweenTheGateAndTheLock(
            () -> {
              assertThat(
                      new JdbcTemplate(dataSource)
                          .update(
                              "UPDATE matrix_call_binding SET end_cause = 'ENDPOINT'"
                                  + " WHERE source_room_id = ? AND call_id = 'call'",
                              source))
                  .isEqualTo(1);
              assertThat(endRows(null)).as("no observation end").isZero();
            });
    assertThat(bound).isFalse();
    var binding = bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow();
    assertThat(binding.getEndedAt()).isNotNull();
    assertThat(binding.getEndCause()).isEqualTo(MatrixCallBinding.END_CAUSE_ENDPOINT);
    assertThat(binding.getCallerMatrixId()).as("the old binding is kept").isEqualTo(askerMatrixId);
    assertThat(binding.getMediaRoomId()).isEqualTo(askerMedia);
    assertThat(endRows(null)).isZero();
  }

  private void serverClosedTheAskersBinding() {
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE matrix_call_binding SET ended_at = ?, end_cause = 'LIFECYCLE'"
                + " WHERE source_room_id = ? AND call_id = 'call'",
            System.currentTimeMillis(),
            source);
  }

  /** End rows of this call, of one actor or ({@code null}) of anybody. */
  private int endRows(String actorMatrixId) {
    var jdbc = new JdbcTemplate(dataSource);
    return actorMatrixId == null
        ? jdbc.queryForObject(
            "SELECT COUNT(*) FROM matrix_call_observation_end WHERE source_room_id = ?"
                + " AND call_id = 'call'",
            Integer.class,
            source)
        : jdbc.queryForObject(
            "SELECT COUNT(*) FROM matrix_call_observation_end WHERE source_room_id = ?"
                + " AND call_id = 'call' AND actor_matrix_id = ?",
            Integer.class,
            source,
            actorMatrixId);
  }

  /**
   * CodeRabbit 4585785816: an overlapping schedule. The takeover is paused while it holds the
   * binding lock and is about to take the session lock; the end, which locks the binding first,
   * must then wait on the binding row. A takeover that took the session first would hold nothing
   * there (the end would not wait) or, swapped the other way, hold the session the end needs next
   * (deadlock). The post-commit case above keeps checking the state after both committed.
   */
  @Test
  void anEndOverlappingTheTakeOversLockAcquisitionWaitsOnTheBinding() throws Exception {
    var other =
        runWhileTheTakeOverHoldsOnlyItsFirstLock(() -> observations.end(actor, source, "call"));
    assertThat(other).isEqualTo(MatrixCallObservationService.Outcome.OK);
    var binding = bindings.findBySourceRoomIdAndCallId(source, "call").orElseThrow();
    assertThat(binding.getMediaRoomId()).isEqualTo(counsellorMedia);
    assertThat(binding.getEndedAt()).isNotNull();
  }

  @Test
  void theLifecycleOverlappingTheTakeOversLockAcquisitionWaitsOnTheBinding() throws Exception {
    var other =
        runWhileTheTakeOverHoldsOnlyItsFirstLock(() -> lifecycle.handleRoom(askerMedia, Map.of()));
    assertThat(other).isEqualTo(false);
  }

  /**
   * Pauses bind's write transaction right before it takes the session's call lock, so it holds the
   * binding lock only, then runs {@code concurrent}. The concurrent operation must block on the
   * binding row; released, both finish without an InnoDB deadlock.
   */
  private <T> T runWhileTheTakeOverHoldsOnlyItsFirstLock(
      java.util.concurrent.Callable<T> concurrent) throws Exception {
    long deadlocksBefore = deadlocks();
    var between = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var paused = new AtomicBoolean();
    var bindThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (Thread.currentThread() == bindThread.get()
                  && TransactionSynchronizationManager.isActualTransactionActive()
                  && paused.compareAndSet(false, true)) {
                between.countDown();
                release.await(20, TimeUnit.SECONDS);
              }
              return invocation.callRealMethod();
            })
        .when(conversations)
        .lockCurrentForWriting(org.mockito.ArgumentMatchers.any());
    var pool = Executors.newFixedThreadPool(2);
    var otherThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    try (var connections =
        MariaDbLockWaits.TransactionConnections.watch(transactionManager, dataSource)) {
      Future<Boolean> bound =
          pool.submit(
              () -> {
                bindThread.set(Thread.currentThread());
                return sessionBindings.bind(actor, source, "call", counsellorMedia, true);
              });
      assertThat(between.await(15, TimeUnit.SECONDS)).isTrue();
      Future<T> other =
          pool.submit(
              () -> {
                otherThread.set(Thread.currentThread());
                return concurrent.call();
              });
      MariaDbLockWaits.awaitLockWait(DATABASE, connections.of(otherThread), "matrix_call_binding");
      release.countDown();
      assertThat(bound.get(30, TimeUnit.SECONDS)).isTrue();
      T result = other.get(30, TimeUnit.SECONDS);
      assertThat(deadlocks()).as("InnoDB deadlocks").isEqualTo(deadlocksBefore);
      return result;
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * Runs bind, pauses it right after its gate and before its transaction (revoking the old call's
   * guests is the gate's last step and runs with no transaction open), lets {@code meanwhile}
   * commit, and returns bind's answer.
   */
  private boolean bindWithACommitBetweenTheGateAndTheLock(Runnable meanwhile) throws Exception {
    var gatePassed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var bindThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var outsideTransaction = new AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (Thread.currentThread() == bindThread.get()) {
                outsideTransaction.set(
                    !TransactionSynchronizationManager.isActualTransactionActive());
                gatePassed.countDown();
                release.await(20, TimeUnit.SECONDS);
              }
              return invocation.callRealMethod();
            })
        .when(callGuests)
        .revokeBeforeTakeOver(org.mockito.ArgumentMatchers.any());
    var pool = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> bound =
          pool.submit(
              () -> {
                bindThread.set(Thread.currentThread());
                return sessionBindings.bind(actor, source, "call", counsellorMedia, true);
              });
      assertThat(gatePassed.await(15, TimeUnit.SECONDS)).isTrue();
      assertThat(outsideTransaction).as("barrier outside any transaction").isTrue();
      meanwhile.run();
      release.countDown();
      return bound.get(30, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private <T> T runWhileTheTakeOverHoldsItsLocks(java.util.concurrent.Callable<T> concurrent)
      throws Exception {
    long deadlocksBefore = deadlocks();
    var inside = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var paused = new AtomicBoolean();
    var bindThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    // The first account check inside bind's write transaction: by then it holds its locks.
    when(accountLifecycle.snapshot(counsellor.getId()))
        .thenAnswer(
            invocation -> {
              if (Thread.currentThread() == bindThread.get()
                  && TransactionSynchronizationManager.isActualTransactionActive()
                  && paused.compareAndSet(false, true)) {
                inside.countDown();
                release.await(20, TimeUnit.SECONDS);
              }
              return Optional.empty();
            });
    var pool = Executors.newFixedThreadPool(2);
    var otherThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    try (var connections =
        MariaDbLockWaits.TransactionConnections.watch(transactionManager, dataSource)) {
      Future<Boolean> bound =
          pool.submit(
              () -> {
                bindThread.set(Thread.currentThread());
                return sessionBindings.bind(actor, source, "call", counsellorMedia, true);
              });
      assertThat(inside.await(15, TimeUnit.SECONDS)).isTrue();
      Future<T> other =
          pool.submit(
              () -> {
                otherThread.set(Thread.currentThread());
                return concurrent.call();
              });
      // Deterministic: the concurrent operation's own connection is blocked on the binding row the
      // takeover holds (both lock the binding first).
      MariaDbLockWaits.awaitLockWait(DATABASE, connections.of(otherThread), "matrix_call_binding");
      release.countDown();
      assertThat(bound.get(30, TimeUnit.SECONDS)).isTrue();
      T result = other.get(30, TimeUnit.SECONDS);
      assertThat(deadlocks()).as("InnoDB deadlocks").isEqualTo(deadlocksBefore);
      return result;
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private long deadlocks() {
    return Long.parseLong(
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT VARIABLE_VALUE FROM information_schema.GLOBAL_STATUS"
                    + " WHERE VARIABLE_NAME = 'INNODB_DEADLOCKS'",
                String.class));
  }

  private static User user(String id, String matrixId) {
    return User.builder()
        .userId(id)
        .username(id)
        .email(id + "@synthetic.oriso.test")
        .matrixUserId(matrixId)
        .tenantId(7L)
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .languageCode(LanguageCode.de)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now())
        .build();
  }

  private static Consultant consultant() {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(7L);
    consultant.setCreateDate(LocalDateTime.now());
    consultant.setUpdateDate(LocalDateTime.now());
    consultant.setUsername("synthetic-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("C" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
    return consultant;
  }
}
