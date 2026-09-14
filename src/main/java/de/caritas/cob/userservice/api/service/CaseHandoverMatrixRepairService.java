package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists and retries Matrix membership compensation independently of handover transactions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverMatrixRepairService {

  private final @NonNull CaseHandoverMatrixRepairTaskRepository repository;
  private final @NonNull CaseHandoverRequestRepository handoverRequestRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull Clock clock;
  private final @NonNull PlatformTransactionManager transactionManager;

  @Value("${case.handover.matrix-repair.max-attempts:100}")
  private int maxAttempts;

  @Value("${case.handover.matrix-repair.retry-backoff:PT1M}")
  private Duration retryBackoff;

  public void enqueueRemoval(
      String roomId,
      String memberId,
      String operatorId,
      Long sessionId,
      String requesterConsultantId) {
    enqueue(
        CaseHandoverMatrixRepairAction.REMOVE,
        roomId,
        memberId,
        operatorId,
        sessionId,
        requesterConsultantId);
  }

  public void enqueueJoin(
      String roomId, String memberId, Long sessionId, String requesterConsultantId) {
    enqueue(
        CaseHandoverMatrixRepairAction.JOIN,
        roomId,
        memberId,
        null,
        sessionId,
        requesterConsultantId);
  }

  private void enqueue(
      CaseHandoverMatrixRepairAction action,
      String roomId,
      String memberId,
      String operatorId,
      Long sessionId,
      String requesterConsultantId) {
    try {
      inNewTransaction(
          () -> {
            repository.saveAndFlush(
                CaseHandoverMatrixRepairTask.builder()
                    .action(action)
                    .roomId(roomId)
                    .memberId(memberId)
                    .sessionId(sessionId)
                    .requesterConsultantId(requesterConsultantId)
                    .operatorId(operatorId)
                    .createDate(LocalDateTime.now(clock))
                    .build());
            return null;
          });
    } catch (DataIntegrityViolationException duplicate) {
      boolean reactivated =
          Boolean.TRUE.equals(
              inNewTransaction(
                  () ->
                      repository
                          .findExistingForUpdate(action, roomId, memberId)
                          .map(
                              existing -> {
                                existing.setOperatorId(operatorId);
                                existing.setSessionId(sessionId);
                                existing.setRequesterConsultantId(requesterConsultantId);
                                existing.setAttemptCount(0);
                                existing.setLastAttemptAt(null);
                                existing.setCreateDate(LocalDateTime.now(clock));
                                repository.saveAndFlush(existing);
                                return true;
                              })
                          .orElse(false)));
      if (!reactivated) {
        throw duplicate;
      }
      log.debug("Reactivated existing Case Handover Matrix repair {}", action);
    }
  }

  @Transactional(readOnly = true)
  public List<Long> pendingTaskIds() {
    return repository
        .findRetryable(
            maxAttempts, LocalDateTime.now(clock).minus(retryBackoff), PageRequest.of(0, 100))
        .stream()
        .map(CaseHandoverMatrixRepairTask::getId)
        .toList();
  }

  /** Matrix I/O runs without a database transaction; only the result update is transactional. */
  public void process(Long taskId) {
    var task = repository.findById(taskId);
    if (task.isEmpty()) {
      return;
    }
    boolean repaired = attempt(task.get());
    recordResult(taskId, repaired);
  }

  private boolean attempt(CaseHandoverMatrixRepairTask task) {
    try {
      boolean activeAccess = hasActiveAccess(task);
      if (task.getAction() == CaseHandoverMatrixRepairAction.REMOVE && activeAccess) {
        return true;
      }
      String loginId =
          task.getAction() == CaseHandoverMatrixRepairAction.JOIN
              ? task.getMemberId()
              : task.getOperatorId();
      if (isBlank(loginId)) {
        return false;
      }
      String token = matrixSynapseService.loginAsUserAccessToken(loginId);
      if (isBlank(token)) {
        return false;
      }
      return switch (task.getAction()) {
        case JOIN -> reconcileJoin(task, token, activeAccess);
        case REMOVE -> removeOrConfirmAbsent(task, token);
      };
    } catch (RuntimeException exception) {
      log.warn(
          "Case Handover Matrix repair task {} failed with {}",
          task.getId(),
          exception.getClass().getSimpleName());
      return false;
    }
  }

  private boolean reconcileJoin(
      CaseHandoverMatrixRepairTask task, String memberToken, boolean activeBeforeJoin) {
    if (!activeBeforeJoin) {
      return leaveOrConfirmAbsent(task, memberToken);
    }
    if (!matrixSynapseService.joinRoom(task.getRoomId(), memberToken)) {
      return false;
    }
    // Access can expire or be revoked while the technical login/JOIN request is in flight. Never
    // retain a membership based only on the pre-I/O check.
    return hasActiveAccess(task) || leaveOrConfirmAbsent(task, memberToken);
  }

  private boolean leaveOrConfirmAbsent(CaseHandoverMatrixRepairTask task, String memberToken) {
    var members = matrixSynapseService.getRoomMembers(task.getRoomId());
    if (members.isPresent() && !members.get().contains(task.getMemberId())) {
      return true;
    }
    if (matrixSynapseService.leaveRoom(task.getRoomId(), memberToken)) {
      return true;
    }
    return matrixSynapseService
        .getRoomMembers(task.getRoomId())
        .map(current -> !current.contains(task.getMemberId()))
        .orElse(false);
  }

  private boolean hasActiveAccess(CaseHandoverMatrixRepairTask task) {
    LocalDateTime now = LocalDateTime.now(clock);
    return handoverRequestRepository
        .findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            task.getSessionId(), task.getRequesterConsultantId())
        .stream()
        .anyMatch(
            request ->
                (request.getStatus() == CaseHandoverRequest.Status.GRANTED
                        || request.getStatus()
                            == CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)
                    && (request.getExpiresAt() == null || request.getExpiresAt().isAfter(now)));
  }

  private boolean removeOrConfirmAbsent(CaseHandoverMatrixRepairTask task, String token) {
    if (matrixSynapseService.removeUserFromRoom(task.getRoomId(), task.getMemberId(), token)) {
      return true;
    }
    return matrixSynapseService
        .getRoomMembers(task.getRoomId())
        .map(members -> !members.contains(task.getMemberId()))
        .orElse(false);
  }

  public void recordResult(Long taskId, boolean repaired) {
    inNewTransaction(
        () -> {
          repository
              .findById(taskId)
              .ifPresent(
                  task -> {
                    if (repaired) {
                      repository.delete(task);
                    } else {
                      task.setAttemptCount(task.getAttemptCount() + 1);
                      task.setLastAttemptAt(LocalDateTime.now(clock));
                      repository.save(task);
                    }
                  });
          return null;
        });
  }

  private <T> T inNewTransaction(java.util.function.Supplier<T> action) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(status -> action.get());
  }
}
