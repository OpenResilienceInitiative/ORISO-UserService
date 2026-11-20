package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportRoom;
import de.caritas.cob.userservice.api.model.SupportRoom.SupportRoomStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.port.out.SupportRoomRepository;
import de.caritas.cob.userservice.api.service.handshake.HandshakePurpose;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService;
import de.caritas.cob.userservice.api.service.notification.GroupChatReminderService;
import de.caritas.cob.userservice.api.service.support.SupportRoomService;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.groupchatreminder.scheduler.GroupChatReminderScheduler;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Proves, against real MariaDB, that two overlapping instances run the handshake sweep, the
 * support-room sweep and the group-chat reminder job once. The lease row is locked by InnoDB, which
 * H2 cannot emulate (see {@code DeleteUsersRegisteredOnlySchedulerMariaDbReplicaIT}).
 *
 * <p>Each scenario widens the overlap window by holding the first instance inside its work for a
 * moment, so a second instance without a claim would read the same pending rows.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class ReplicaSweepClaimMariaDbIT {

  private static final String HANDSHAKE_ID = "replica-proof-handshake";
  private static final String ROOM_ID = "replica-proof-support-room";
  private static final String MATRIX_ROOM_ID = "!replica-proof:matrix.test";
  private static final String ADMIN_MATRIX_ID = "@replica-proof-admin:matrix.test";
  private static final long OVERLAP_MILLIS = 750;

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add(
        "spring.datasource.password",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_PASSWORD", "root"));
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
    registry.add("handshake.sweep-delay-ms", () -> "3600000");
    registry.add("support.room-sweep-delay-ms", () -> "3600000");
    registry.add("group.chat.reminder.cron", () -> "0 0 0 1 1 ?");
  }

  @Autowired private HandshakeService handshakeService;
  @Autowired private SupportRoomService supportRoomService;
  @Autowired private HandshakeSessionRepository handshakeSessionRepository;
  @Autowired private SupportRoomRepository supportRoomRepository;
  @Autowired private ScheduledTaskClaimRepository claimRepository;
  @Autowired private ScheduledTaskClaimService claimService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ScheduledAnnotationBeanPostProcessor scheduledTasks;
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  /**
   * The application's own {@code @Scheduled} handshake sweep would otherwise join the scenario as
   * an uncontrolled third instance; the scenarios start every sweep themselves.
   */
  @BeforeEach
  void stopBackgroundHandshakeSweep() {
    scheduledTasks.getScheduledTasks().stream()
        .filter(task -> task.getTask().toString().contains("HandshakeService.sweepExpired"))
        .forEach(ScheduledTask::cancel);
  }

  @BeforeEach
  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM handshake_audit_event WHERE handshake_id = ?", HANDSHAKE_ID);
    jdbcTemplate.update("DELETE FROM support_room WHERE id = ?", ROOM_ID);
    jdbcTemplate.update("DELETE FROM handshake_session WHERE id = ?", HANDSHAKE_ID);
    for (var task :
        new String[] {HandshakeService.SWEEP_TASK_NAME, GroupChatReminderScheduler.TASK_NAME}) {
      claimRepository.findById(task).ifPresent(claimRepository::delete);
    }
  }

  @Test
  void twoInstancesExpireOneLapsedHandshakeAndAuditItOnce() throws Exception {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    handshakeSessionRepository.save(
        HandshakeSession.builder()
            .id(HANDSHAKE_ID)
            .purpose(HandshakePurpose.SUPPORT_ACCESS)
            .initiatorId("replica-proof-initiator")
            .counterpartId("replica-proof-counterpart")
            .status(HandshakeStatus.PENDING)
            .createDate(now.minusMinutes(10))
            .expiryDate(now.minusMinutes(5))
            .build());

    // Pause the first instance after it has read the lapsed session and before it writes it.
    // Release it only once the database shows the second instance has moved: it either waits on
    // the held lease row (claim serializes) or has read the same pending session (claim missing or
    // not serializing). In the latter case both instances hold the PENDING row, so the session is
    // written twice and the test fails.
    var firstReadPending = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var reads = new AtomicInteger();
    var saves = new AtomicInteger();
    HandshakeSessionRepository pausing =
        mock(
            HandshakeSessionRepository.class,
            withSettings()
                .defaultAnswer(AdditionalAnswers.delegatesTo(handshakeSessionRepository)));
    doAnswer(
            invocation -> {
              var pending =
                  handshakeSessionRepository.findAllByStatusAndExpiryDateBefore(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2));
              reads.incrementAndGet();
              return pending;
            })
        .when(pausing)
        .findAllByStatusAndExpiryDateBefore(any(), any(), any());
    doAnswer(
            invocation -> {
              if (saves.incrementAndGet() == 1) {
                firstReadPending.countDown();
                awaitOrFail(releaseFirst);
              }
              return handshakeSessionRepository.save(invocation.<HandshakeSession>getArgument(0));
            })
        .when(pausing)
        .save(any(HandshakeSession.class));

    var target = AopTestUtils.<HandshakeService>getUltimateTargetObject(handshakeService);
    ReflectionTestUtils.setField(target, "handshakeSessionRepository", pausing);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> handshakeService.sweepExpired());
      var firstReachedWrite = firstReadPending.await(10, TimeUnit.SECONDS);
      if (!firstReachedWrite && first.isDone()) {
        first.get(); // surface why the first instance stopped before writing
      }
      assertThat(firstReachedWrite)
          .as("the first instance read the lapsed session and is about to write it")
          .isTrue();
      var second = executor.submit(() -> handshakeService.sweepExpired());
      assertThat(awaitSecondInstanceMoved(second, reads))
          .as("the second instance waits on the lease row or reads the pending session")
          .isTrue();
      releaseFirst.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
      ReflectionTestUtils.setField(
          target, "handshakeSessionRepository", handshakeSessionRepository);
    }
    assertThat(saves).as("the session is written exactly once").hasValue(1);

    assertThat(handshakeSessionRepository.findById(HANDSHAKE_ID))
        .get()
        .extracting(HandshakeSession::getStatus)
        .isEqualTo(HandshakeStatus.EXPIRED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM handshake_audit_event WHERE handshake_id = ?",
                Integer.class,
                HANDSHAKE_ID))
        .isEqualTo(1);
    assertThat(claimRepository.findById(HandshakeService.SWEEP_TASK_NAME))
        .as("the lease is released so the next tick can run")
        .isEmpty();
  }

  @Test
  void twoInstancesCloseOneExpiredSupportRoomOnce() throws Exception {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    supportRoomRepository.save(
        SupportRoom.builder()
            .id(ROOM_ID)
            .handshakeId(HANDSHAKE_ID)
            .matrixRoomId(MATRIX_ROOM_ID)
            .supportAdminId("replica-proof-admin")
            .supportAdminMatrixId(ADMIN_MATRIX_ID)
            .consultantId("replica-proof-consultant")
            .status(SupportRoomStatus.ACTIVE)
            .createDate(now.minusHours(5))
            .expiryDate(now.minusHours(1))
            .build());
    when(matrixSynapseService.loginAsUserAccessToken(ADMIN_MATRIX_ID)).thenReturn("token");
    doAnswer(
            invocation -> {
              Thread.sleep(OVERLAP_MILLIS);
              return true;
            })
        .when(matrixSynapseService)
        .leaveRoom(MATRIX_ROOM_ID, "token");
    when(matrixSynapseService.isOutOfRoom(MATRIX_ROOM_ID, ADMIN_MATRIX_ID))
        .thenReturn(Optional.of(true));

    runConcurrently(
        () -> {
          supportRoomService.sweepExpired();
          return null;
        });

    verify(matrixSynapseService, times(1)).leaveRoom(MATRIX_ROOM_ID, "token");
    assertThat(supportRoomRepository.findById(ROOM_ID))
        .get()
        .extracting(SupportRoom::getStatus, SupportRoom::getCloseReason)
        .containsExactly(SupportRoomStatus.CLOSED, "EXPIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM handshake_audit_event WHERE handshake_id = ?"
                    + " AND event = 'SUPPORT_ROOM_CLOSED'",
                Integer.class,
                HANDSHAKE_ID))
        .isEqualTo(1);
  }

  @Test
  void twoSchedulerInstancesPublishRemindersOnce() throws Exception {
    var reminderService = mock(GroupChatReminderService.class);
    doAnswer(
            invocation -> {
              Thread.sleep(OVERLAP_MILLIS);
              return null;
            })
        .when(reminderService)
        .publishUpcomingReminders(any());
    var first = newReminderScheduler(reminderService);
    var second = newReminderScheduler(reminderService);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult =
          executor.submit(() -> startTogether(first::publishUpcomingReminders, ready, start));
      var secondResult =
          executor.submit(() -> startTogether(second::publishUpcomingReminders, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult.get(15, TimeUnit.SECONDS);
      secondResult.get(15, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    verify(reminderService, times(1)).publishUpcomingReminders(any());
    assertThat(claimRepository.findById(GroupChatReminderScheduler.TASK_NAME)).isEmpty();
  }

  private GroupChatReminderScheduler newReminderScheduler(GroupChatReminderService service) {
    var scheduler =
        new GroupChatReminderScheduler(service, mock(TenantContextProvider.class), claimService);
    ReflectionTestUtils.setField(scheduler, "enabled", true);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofMinutes(4));
    return scheduler;
  }

  private boolean awaitSecondInstanceMoved(
      java.util.concurrent.Future<?> second, AtomicInteger reads) throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (reads.get() >= 2 || second.isDone() || lockWaits() > 0) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
  }

  /**
   * Another connection is executing the locking read of the handshake lease row right now, i.e. the
   * second instance is waiting for the first instance's lease. The observer's own connection is
   * excluded, and the statement must target the handshake claim row.
   */
  private int lockWaits() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.PROCESSLIST"
            + " WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query'"
            + " AND INFO LIKE CONCAT('%scheduled\\_task\\_claim%', ?, '%for update%')",
        Integer.class, HandshakeService.SWEEP_TASK_NAME);
  }

  private static void awaitOrFail(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS))
          .as("the first instance is released in time")
          .isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private void runConcurrently(Callable<Void> sweep) throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> startTogether(sweep, ready, start));
      var second = executor.submit(() -> startTogether(sweep, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private Void startTogether(Callable<Void> work, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for concurrent replica proof");
    }
    return work.call();
  }

  private Void startTogether(Runnable work, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    return startTogether(
        () -> {
          work.run();
          return null;
        },
        ready,
        start);
  }
}
