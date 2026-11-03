package de.caritas.cob.userservice.api.workflow.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  @MockitoBean private MatrixSynapseService matrixSynapseService;

  @BeforeEach
  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM handshake_audit_event WHERE handshake_id = ?", HANDSHAKE_ID);
    jdbcTemplate.update("DELETE FROM support_room WHERE id = ?", ROOM_ID);
    jdbcTemplate.update("DELETE FROM handshake_session WHERE id = ?", HANDSHAKE_ID);
    for (var task :
        new String[] {
          HandshakeService.SWEEP_TASK_NAME,
          SupportRoomService.SWEEP_TASK_NAME,
          GroupChatReminderScheduler.TASK_NAME
        }) {
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

    runConcurrently(
        () -> {
          handshakeService.sweepExpired();
          return null;
        });

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
    assertThat(claimRepository.findById(SupportRoomService.SWEEP_TASK_NAME)).isEmpty();
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
