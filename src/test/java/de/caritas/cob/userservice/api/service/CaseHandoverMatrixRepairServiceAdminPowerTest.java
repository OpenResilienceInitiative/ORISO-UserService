package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.FakeSynapseHomeserver;
import de.caritas.cob.userservice.api.adapters.matrix.FakeSynapseWithSelfLeave;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixRoomClient;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * #1421, finding from #1424: the repair queue's LEAVE for a counsellor who moved to another Träger
 * first makes the technical Matrix admin the room's power holder. The agency account is gone, so
 * once the counsellor left nobody could raise the colleague who takes the case over (co-access,
 * supervision and a second handover would fail later). The homeserver is a stateful double: the
 * admin only ends up at power 100 when the production gateway really got it there, and a counsellor
 * only leaves when the leave request is really sent.
 */
class CaseHandoverMatrixRepairServiceAdminPowerTest {

  private static final String ROOM_METRIC = "userservice.matrix.leave.admin_power.failed";
  private static final String MOVER_MXID = FakeSynapseHomeserver.mxid("mover1421");
  private static final String ASKER_MXID = FakeSynapseHomeserver.mxid("asker1421");
  private static final String OWNER_MXID = FakeSynapseHomeserver.mxid("owner1421");
  private static final String ADMIN_MXID = FakeSynapseHomeserver.adminMxid();

  private final CaseHandoverMatrixRepairTaskRepository repository =
      mock(CaseHandoverMatrixRepairTaskRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final List<ch.qos.logback.classic.Logger> watchedLoggers =
      List.of(
          (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MatrixSynapseService.class),
          (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MatrixRoomClient.class),
          (ch.qos.logback.classic.Logger)
              LoggerFactory.getLogger(CaseHandoverMatrixRepairService.class));

  private FakeSynapseHomeserver homeserver;
  private MatrixSynapseService gateway;
  private CaseHandoverMatrixRepairService service;

  @BeforeEach
  void setUp() {
    homeserver = new FakeSynapseHomeserver();
    gateway = spy(new FakeSynapseWithSelfLeave(homeserver).gateway());
    var transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    service =
        new CaseHandoverMatrixRepairService(
            repository,
            mock(CaseHandoverRequestRepository.class),
            gateway,
            mock(SessionRepository.class),
            Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC),
            transactionManager,
            meters);
    ReflectionTestUtils.setField(service, "maxAttempts", 100);
    ReflectionTestUtils.setField(service, "retryBackoff", Duration.ofMinutes(1));
    logs.start();
    watchedLoggers.forEach(
        logger -> {
          logger.setLevel(Level.ALL);
          logger.addAppender(logs);
        });
  }

  @AfterEach
  void tearDown() {
    watchedLoggers.forEach(
        logger -> {
          logger.detachAppender(logs);
          logger.setLevel(null);
        });
  }

  @Test
  void theAdminHoldsPower100WhenTheCounsellorHasLeft() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0).member(room, ADMIN_MXID, 0);
    var task = leaveTask(11L, room);

    service.process(11L);

    assertThat(homeserver.isMember(room, MOVER_MXID)).isFalse();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
    assertThat(homeserver.isMember(room, ADMIN_MXID)).isTrue();
    verify(repository).delete(task);
  }

  @Test
  void anAdminWhoIsNotInTheRoomIsBroughtInBeforeTheCounsellorLeaves() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0);
    leaveTask(12L, room);

    service.process(12L);

    assertThat(homeserver.members(room)).containsExactlyInAnyOrder(ASKER_MXID, ADMIN_MXID);
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
  }

  @Test
  void aFailedHandOverKeepsTheCounsellorInTheRoomAndTheTaskForTheNextTry() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0).member(room, ADMIN_MXID, 0);
    var task = leaveTask(13L, room);
    doReturn(false).doCallRealMethod().when(gateway).ensureAdminHoldsRoomPower(any(), any(), any());

    service.process(13L);

    assertThat(homeserver.isMember(room, MOVER_MXID)).as("not left without the hand-over").isTrue();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isZero();
    assertThat(task.getAttemptCount()).isEqualTo(1);
    verify(repository).save(task);
    verify(repository, never()).delete(task);
    verify(gateway, never()).leaveRoom(anyString(), anyString());

    service.process(13L);

    assertThat(homeserver.isMember(room, MOVER_MXID)).isFalse();
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
    verify(repository).delete(task);
  }

  @Test
  void everyFailedHandOverIsCountedAndLoggedWithoutMatrixIds() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0).member(room, ADMIN_MXID, 0);
    leaveTask(14L, room);
    doReturn(false).when(gateway).ensureAdminHoldsRoomPower(any(), any(), any());

    service.process(14L);
    service.process(14L);

    assertThat(meters.get(ROOM_METRIC).counter().count()).isEqualTo(2.0);
    var warning =
        logs.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.contains("power"))
            .findFirst();
    assertThat(warning).isPresent();
    assertThat(warning.get()).contains("session 123");
    assertNoMatrixIdInLogs();
  }

  @Test
  void aSuccessfulLeaveNeverLogsAMatrixIdAndCountsNothing() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0);
    leaveTask(15L, room);

    service.process(15L);

    assertThat(homeserver.isMember(room, MOVER_MXID)).isFalse();
    assertThat(meters.find(ROOM_METRIC).counter()).isNull();
    assertThat(logs.list).isNotEmpty();
    assertNoMatrixIdInLogs();
  }

  @Test
  void aCounsellorWhoIsOnlyASilentMemberLeavesAndTheRoomKeepsItsOwner() {
    String room = homeserver.createRoom(OWNER_MXID);
    homeserver.member(room, MOVER_MXID, 0).member(room, ADMIN_MXID, 0);
    var task = leaveTask(16L, room);

    service.process(16L);

    assertThat(homeserver.isMember(room, MOVER_MXID)).isFalse();
    assertThat(homeserver.powerOf(room, OWNER_MXID)).isEqualTo(100);
    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isZero();
    verify(repository).delete(task);
  }

  @Test
  void aTaskForACounsellorWhoAlreadyLeftIsDoneWithoutTouchingThePowerLevels() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ADMIN_MXID, 0).memberLeaves(room, MOVER_MXID);
    var task = leaveTask(17L, room);

    service.process(17L);

    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isZero();
    verify(gateway, never()).ensureAdminHoldsRoomPower(any(), any(), any());
    verify(repository).delete(task);
  }

  @Test
  void runningTheSameLeaveTwiceIsHarmless() {
    String room = homeserver.createRoom(MOVER_MXID);
    homeserver.member(room, ASKER_MXID, 0).member(room, ADMIN_MXID, 0);
    leaveTask(18L, room);
    var again = leaveTask(19L, room);

    service.process(18L);
    service.process(19L);

    assertThat(homeserver.powerOf(room, ADMIN_MXID)).isEqualTo(100);
    assertThat(homeserver.members(room)).containsExactlyInAnyOrder(ASKER_MXID, ADMIN_MXID);
    verify(repository).delete(again);
  }

  @Test
  void theHandOverBelongsToTheLeaveAndNotToAJoinRepair() {
    String room = homeserver.createRoom(OWNER_MXID);
    homeserver.member(room, MOVER_MXID, 0).member(room, ADMIN_MXID, 0);
    var join = leaveTask(20L, room);
    join.setAction(CaseHandoverMatrixRepairAction.JOIN);

    service.process(20L);

    verify(gateway, never()).ensureAdminHoldsRoomPower(any(), any(), any());
  }

  private CaseHandoverMatrixRepairTask leaveTask(long id, String roomId) {
    var task =
        CaseHandoverMatrixRepairTask.builder()
            .id(id)
            .action(CaseHandoverMatrixRepairAction.LEAVE)
            .roomId(roomId)
            .memberId(MOVER_MXID)
            .sessionId(123L)
            .requesterConsultantId("mover")
            .build();
    when(repository.findById(id)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(task));
    return task;
  }

  private void assertNoMatrixIdInLogs() {
    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("@"))
        .noneMatch(message -> message.contains("mover1421"))
        .noneMatch(message -> message.contains("asker1421"))
        .noneMatch(message -> message.contains(FakeSynapseHomeserver.ADMIN_LOCALPART));
  }
}
