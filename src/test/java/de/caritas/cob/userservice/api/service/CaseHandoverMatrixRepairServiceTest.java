package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
