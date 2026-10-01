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

  private CaseHandoverMatrixRepairService service;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    service =
        new CaseHandoverMatrixRepairService(
            repository,
            handoverRequestRepository,
            matrixSynapseService,
            Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC),
            transactionManager);
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
}
