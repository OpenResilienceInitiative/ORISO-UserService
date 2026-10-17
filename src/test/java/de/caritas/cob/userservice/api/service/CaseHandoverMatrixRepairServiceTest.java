package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class CaseHandoverMatrixRepairServiceTest {

  @Mock private CaseHandoverMatrixRepairTaskRepository repository;
  @Mock private CaseHandoverRequestRepository handoverRequestRepository;
  @Mock private MatrixSynapseService matrixSynapseService;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus transactionStatus;

  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

  private CaseHandoverMatrixRepairService service;

  @BeforeEach
  void setUp() {
    // lenient: enqueueLeaves/cancelPendingLeaves join the caller's transaction and open none.
    org.mockito.Mockito.lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(transactionStatus);
    service =
        new CaseHandoverMatrixRepairService(
            repository,
            handoverRequestRepository,
            matrixSynapseService,
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.port.out.SessionRepository.class),
            Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC),
            transactionManager,
            meters);
    ReflectionTestUtils.setField(service, "maxAttempts", 100);
    ReflectionTestUtils.setField(service, "retryBackoff", Duration.ofMinutes(1));
  }

  @Test
  void enqueueRemovalPersistsFreshCredentialsReferenceOutsideTheRolledBackHandover() {
    service.enqueueRemoval(
        "!room:matrix", "@requester:matrix", "@operator:matrix", 123L, "requester");

    var captor = ArgumentCaptor.forClass(CaseHandoverMatrixRepairTask.class);
    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getAction()).isEqualTo(CaseHandoverMatrixRepairAction.REMOVE);
    assertThat(captor.getValue().getRoomId()).isEqualTo("!room:matrix");
    assertThat(captor.getValue().getMemberId()).isEqualTo("@requester:matrix");
    assertThat(captor.getValue().getOperatorId()).isEqualTo("@operator:matrix");
    assertThat(captor.getValue().getSessionId()).isEqualTo(123L);
    assertThat(captor.getValue().getRequesterConsultantId()).isEqualTo("requester");
    assertThat(captor.getValue().getCreateDate())
        .isEqualTo(LocalDateTime.parse("2026-09-14T10:00:00"));
  }

  @Test
  void enqueueRemovalReactivatesAnExhaustedDuplicateTask() {
    var exhausted =
        CaseHandoverMatrixRepairTask.builder()
            .id(7L)
            .action(CaseHandoverMatrixRepairAction.REMOVE)
            .roomId("!room:matrix")
            .memberId("@requester:matrix")
            .sessionId(100L)
            .requesterConsultantId("old-requester")
            .operatorId("@old-operator:matrix")
            .attemptCount(100)
            .generation(4)
            .lastAttemptAt(LocalDateTime.parse("2026-09-14T09:00:00"))
            .createDate(LocalDateTime.parse("2026-09-14T08:00:00"))
            .build();
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate"))
        .doReturn(exhausted)
        .when(repository)
        .saveAndFlush(any(CaseHandoverMatrixRepairTask.class));
    when(repository.findExistingForUpdate(
            CaseHandoverMatrixRepairAction.REMOVE, "!room:matrix", "@requester:matrix"))
        .thenReturn(Optional.of(exhausted));
    service.enqueueRemoval(
        "!room:matrix", "@requester:matrix", "@new-operator:matrix", 123L, "requester");

    assertThat(exhausted.getAttemptCount()).isZero();
    assertThat(exhausted.getGeneration()).isEqualTo(5);
    assertThat(exhausted.getLastAttemptAt()).isNull();
    assertThat(exhausted.getSessionId()).isEqualTo(123L);
    assertThat(exhausted.getRequesterConsultantId()).isEqualTo("requester");
    assertThat(exhausted.getOperatorId()).isEqualTo("@new-operator:matrix");
  }

  @Test
  void enqueueRemovalRethrowsAnUnrelatedIntegrityViolation() {
    doThrow(new DataIntegrityViolationException("not the repair uniqueness constraint"))
        .when(repository)
        .saveAndFlush(any(CaseHandoverMatrixRepairTask.class));
    when(repository.findExistingForUpdate(
            CaseHandoverMatrixRepairAction.REMOVE, "!room:matrix", "@requester:matrix"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.enqueueRemoval(
                    "!room:matrix", "@requester:matrix", "@operator:matrix", 123L, "requester"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void processRemovalUsesAFreshTokenAndDeletesSuccessfulTask() {
    var task =
        CaseHandoverMatrixRepairTask.builder()
            .id(9L)
            .action(CaseHandoverMatrixRepairAction.REMOVE)
            .roomId("!room:matrix")
            .memberId("@requester:matrix")
            .sessionId(123L)
            .requesterConsultantId("requester")
            .operatorId("@operator:matrix")
            .build();
    when(repository.findById(9L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(task));
    when(matrixSynapseService.loginAsUserAccessToken("@operator:matrix")).thenReturn("fresh-token");
    when(matrixSynapseService.removeUserFromRoom(
            "!room:matrix", "@requester:matrix", "fresh-token"))
        .thenReturn(true);

    service.process(9L);

    verify(repository).delete(task);
  }

  @Test
  void processRetainsFailedTaskForBackedOffRetry() {
    var task =
        CaseHandoverMatrixRepairTask.builder()
            .id(9L)
            .action(CaseHandoverMatrixRepairAction.JOIN)
            .roomId("!room:matrix")
            .memberId("@requester:matrix")
            .sessionId(123L)
            .requesterConsultantId("requester")
            .build();
    when(repository.findById(9L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(9L)).thenReturn(Optional.of(task));
    when(handoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(
            java.util.List.of(
                CaseHandoverRequest.builder()
                    .status(CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)
                    .build()));
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("fresh-token");
    when(matrixSynapseService.joinRoom("!room:matrix", "fresh-token")).thenReturn(false);

    service.process(9L);

    assertThat(task.getAttemptCount()).isEqualTo(1);
    assertThat(task.getLastAttemptAt()).isEqualTo(LocalDateTime.parse("2026-09-14T10:00:00"));
    verify(repository).save(task);
  }

  @Test
  void staleRemovalTaskCannotRevokeANewerActiveGrant() {
    var task =
        CaseHandoverMatrixRepairTask.builder()
            .id(10L)
            .action(CaseHandoverMatrixRepairAction.REMOVE)
            .roomId("!room:matrix")
            .memberId("@requester:matrix")
            .sessionId(123L)
            .requesterConsultantId("requester")
            .operatorId("@operator:matrix")
            .build();
    when(repository.findById(10L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(task));
    when(handoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(
            java.util.List.of(
                CaseHandoverRequest.builder().status(CaseHandoverRequest.Status.GRANTED).build()));

    service.process(10L);

    verify(matrixSynapseService, org.mockito.Mockito.never())
        .removeUserFromRoom(any(), any(), any());
    verify(repository).delete(task);
  }

  @Test
  void joinRepairRemovesMembershipWhenAccessExpiresDuringJoin() {
    var task =
        CaseHandoverMatrixRepairTask.builder()
            .id(11L)
            .action(CaseHandoverMatrixRepairAction.JOIN)
            .roomId("!room:matrix")
            .memberId("@requester:matrix")
            .sessionId(123L)
            .requesterConsultantId("requester")
            .build();
    var active = CaseHandoverRequest.builder().status(CaseHandoverRequest.Status.GRANTED).build();
    when(repository.findById(11L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(task));
    when(handoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            123L, "requester"))
        .thenReturn(java.util.List.of(active), java.util.List.of());
    when(matrixSynapseService.loginAsUserAccessToken("@requester:matrix"))
        .thenReturn("fresh-token");
    when(matrixSynapseService.joinRoom("!room:matrix", "fresh-token")).thenReturn(true);
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(java.util.List.of("@requester:matrix")));
    when(matrixSynapseService.leaveRoom("!room:matrix", "fresh-token")).thenReturn(true);

    service.process(11L);

    verify(matrixSynapseService).leaveRoom("!room:matrix", "fresh-token");
    verify(repository).delete(task);
  }

  @Test
  void staleWorkerResultCannotDeleteAReactivatedGeneration() {
    var reactivated =
        CaseHandoverMatrixRepairTask.builder().id(12L).generation(2).attemptCount(0).build();
    when(repository.findByIdForUpdate(12L)).thenReturn(Optional.of(reactivated));

    service.recordResult(12L, 1, true);

    verify(repository, org.mockito.Mockito.never()).delete(any());
    verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void enqueueLeavesQueuesOneDurableTaskPerRoomInsideTheCallersTransaction() {
    when(repository.findByActionAndMemberId(CaseHandoverMatrixRepairAction.LEAVE, "@mover:matrix"))
        .thenReturn(java.util.List.of());

    int queued =
        service.enqueueLeaves(
            "@mover:matrix",
            "mover",
            java.util.List.of(
                new CaseHandoverMatrixRepairService.RoomRef(1L, "!a:matrix"),
                new CaseHandoverMatrixRepairService.RoomRef(2L, "!b:matrix"),
                new CaseHandoverMatrixRepairService.RoomRef(3L, "!a:matrix")));

    assertThat(queued).isEqualTo(2);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<CaseHandoverMatrixRepairTask>> saved =
        ArgumentCaptor.forClass(Iterable.class);
    verify(repository).saveAll(saved.capture());
    assertThat(saved.getValue())
        .extracting(
            CaseHandoverMatrixRepairTask::getAction,
            CaseHandoverMatrixRepairTask::getRoomId,
            CaseHandoverMatrixRepairTask::getMemberId,
            CaseHandoverMatrixRepairTask::getSessionId,
            CaseHandoverMatrixRepairTask::getRequesterConsultantId,
            CaseHandoverMatrixRepairTask::getOperatorId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                CaseHandoverMatrixRepairAction.LEAVE,
                "!a:matrix",
                "@mover:matrix",
                1L,
                "mover",
                null),
            org.assertj.core.groups.Tuple.tuple(
                CaseHandoverMatrixRepairAction.LEAVE,
                "!b:matrix",
                "@mover:matrix",
                2L,
                "mover",
                null));
    // A REQUIRES_NEW write would outlive a rolled-back move and kick the counsellor out anyway.
    verify(repository, org.mockito.Mockito.never())
        .saveAndFlush(any(CaseHandoverMatrixRepairTask.class));
  }

  @Test
  void enqueueLeavesReactivatesAnExhaustedTaskOfAnEarlierMove() {
    var exhausted =
        CaseHandoverMatrixRepairTask.builder()
            .id(5L)
            .action(CaseHandoverMatrixRepairAction.LEAVE)
            .roomId("!a:matrix")
            .memberId("@mover:matrix")
            .sessionId(1L)
            .requesterConsultantId("mover")
            .attemptCount(100)
            .generation(2)
            .lastAttemptAt(LocalDateTime.parse("2026-09-13T09:00:00"))
            .createDate(LocalDateTime.parse("2026-09-13T08:00:00"))
            .build();
    when(repository.findByActionAndMemberId(CaseHandoverMatrixRepairAction.LEAVE, "@mover:matrix"))
        .thenReturn(java.util.List.of(exhausted));

    int queued =
        service.enqueueLeaves(
            "@mover:matrix",
            "mover",
            java.util.List.of(new CaseHandoverMatrixRepairService.RoomRef(1L, "!a:matrix")));

    assertThat(queued).isEqualTo(1);
    assertThat(exhausted.getAttemptCount()).isZero();
    assertThat(exhausted.getLastAttemptAt()).isNull();
    assertThat(exhausted.getGeneration()).isEqualTo(3);
    assertThat(exhausted.getCreateDate()).isEqualTo(LocalDateTime.parse("2026-09-14T10:00:00"));
    verify(repository).save(exhausted);
  }

  @Test
  void cancelPendingLeavesDropsOnlyTheMembersLeavesInThatTenant() {
    service.cancelPendingLeavesInTenant("@mover:matrix", 8L);

    verify(repository)
        .deleteByActionAndMemberIdInTenant(
            CaseHandoverMatrixRepairAction.LEAVE, "@mover:matrix", 8L);
  }

  @Test
  void processLeaveMakesTheMemberLeaveWithTheirOwnTokenEvenWhileAnEarlierGrantIsActive() {
    var task = leaveTask(11L);
    when(repository.findById(11L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(task));
    // An earlier co-access grant would make a REMOVE task a no-op. The move ends it.
    org.mockito.Mockito.lenient()
        .when(
            handoverRequestRepository.findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
                123L, "mover"))
        .thenReturn(
            java.util.List.of(
                CaseHandoverRequest.builder().status(CaseHandoverRequest.Status.GRANTED).build()));
    when(matrixSynapseService.loginAsUserAccessToken("@mover:matrix")).thenReturn("own-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(java.util.List.of("@mover:matrix", "@colleague:matrix")));
    when(matrixSynapseService.ensureAdminHoldsRoomPower(
            "!room:matrix", "@mover:matrix", "own-token"))
        .thenReturn(true);
    when(matrixSynapseService.leaveRoom("!room:matrix", "own-token")).thenReturn(true);

    service.process(11L);

    verify(matrixSynapseService).leaveRoom("!room:matrix", "own-token");
    verify(repository).delete(task);
  }

  @Test
  void processLeaveIsDoneWhenTheMemberIsNotInTheRoomAnymore() {
    var task = leaveTask(12L);
    when(repository.findById(12L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(12L)).thenReturn(Optional.of(task));
    when(matrixSynapseService.loginAsUserAccessToken("@mover:matrix")).thenReturn("own-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(java.util.List.of("@colleague:matrix")));

    service.process(12L);

    verify(matrixSynapseService, org.mockito.Mockito.never()).leaveRoom(any(), any());
    verify(repository).delete(task);
  }

  @Test
  void processLeaveKeepsTheTaskForRetryWhileMatrixRefusesTheLeave() {
    var task = leaveTask(13L);
    when(repository.findById(13L)).thenReturn(Optional.of(task));
    when(repository.findByIdForUpdate(13L)).thenReturn(Optional.of(task));
    when(matrixSynapseService.loginAsUserAccessToken("@mover:matrix")).thenReturn("own-token");
    when(matrixSynapseService.getRoomMembers("!room:matrix"))
        .thenReturn(Optional.of(java.util.List.of("@mover:matrix")));
    when(matrixSynapseService.ensureAdminHoldsRoomPower(
            "!room:matrix", "@mover:matrix", "own-token"))
        .thenReturn(true);
    when(matrixSynapseService.leaveRoom("!room:matrix", "own-token")).thenReturn(false);

    service.process(13L);

    assertThat(task.getAttemptCount()).isEqualTo(1);
    verify(repository).save(task);
    verify(repository, org.mockito.Mockito.never()).delete(task);
  }

  /** #1421 finding 4: a revocation that is given up must not fail silently. */
  @Test
  void aLeaveThatUsedItsLastAttemptIsLoggedAsWarningWithoutPersonalDataAndCounted() {
    var task = leaveTask(21L);
    task.setAttemptCount(99);
    when(repository.findByIdForUpdate(21L)).thenReturn(Optional.of(task));
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(CaseHandoverMatrixRepairService.class);
    logs.start();
    logger.addAppender(logs);
    try {
      service.recordResult(21L, 0, false);
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(task.getAttemptCount()).isEqualTo(100);
    assertThat(meters.counter("userservice.matrix.leave.exhausted").count()).isEqualTo(1.0);
    assertThat(logs.list)
        .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
        .singleElement()
        .satisfies(
            event -> {
              var message = event.getFormattedMessage();
              assertThat(message).contains("123").contains("100");
              // Neither the chat account, nor the room, nor the counsellor's id.
              assertThat(message)
                  .doesNotContain("@mover:matrix")
                  .doesNotContain("!room:matrix")
                  .doesNotContain("mover");
            });
  }

  @Test
  void aLeaveThatStillHasAttemptsLeftIsNeitherWarnedAboutNorCounted() {
    var task = leaveTask(22L);
    task.setAttemptCount(98);
    when(repository.findByIdForUpdate(22L)).thenReturn(Optional.of(task));

    service.recordResult(22L, 0, false);

    assertThat(task.getAttemptCount()).isEqualTo(99);
    assertThat(meters.find("userservice.matrix.leave.exhausted").counter()).isNull();
  }

  @Test
  void aSucceededLeaveIsNotCounted() {
    var task = leaveTask(23L);
    task.setAttemptCount(99);
    when(repository.findByIdForUpdate(23L)).thenReturn(Optional.of(task));

    service.recordResult(23L, 0, true);

    assertThat(meters.find("userservice.matrix.leave.exhausted").counter()).isNull();
  }

  @Test
  void anExhaustedTaskOfAnotherActionIsNotCountedAsAFailedRevocation() {
    var task = leaveTask(24L);
    task.setAction(CaseHandoverMatrixRepairAction.JOIN);
    task.setAttemptCount(99);
    when(repository.findByIdForUpdate(24L)).thenReturn(Optional.of(task));

    service.recordResult(24L, 0, false);

    assertThat(meters.find("userservice.matrix.leave.exhausted").counter()).isNull();
  }

  private static CaseHandoverMatrixRepairTask leaveTask(long id) {
    return CaseHandoverMatrixRepairTask.builder()
        .id(id)
        .action(CaseHandoverMatrixRepairAction.LEAVE)
        .roomId("!room:matrix")
        .memberId("@mover:matrix")
        .sessionId(123L)
        .requesterConsultantId("mover")
        .build();
  }
}
