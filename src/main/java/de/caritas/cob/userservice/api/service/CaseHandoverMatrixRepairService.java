package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists and retries Matrix membership compensation independently of handover transactions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverMatrixRepairService {

  private final @NonNull CaseHandoverMatrixRepairTaskRepository repository;
  private final @NonNull CaseHandoverRequestRepository handoverRequestRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionRepository sessionRepository;
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

  /** Identifies a staged repair row so the caller can complete exactly that generation. */
  public record StagedRepair(Long id, long generation) {}

  /**
   * Persists the owner power raise retry in the CALLER's transaction, so it commits atomically with
   * the takeover (and disappears with it on a rollback). A failure here must propagate: it is the
   * only durable compensation for a new owner left read-only. The retry gives up once another
   * counsellor owns the case.
   */
  public StagedRepair stagePowerRaise(
      String roomId, String memberId, Long sessionId, String requesterConsultantId) {
    var existing =
        repository.findExistingForUpdate(
            CaseHandoverMatrixRepairAction.POWER_RAISE, roomId, memberId);
    CaseHandoverMatrixRepairTask task;
    if (existing.isPresent()) {
      task = existing.get();
      task.setSessionId(sessionId);
      task.setRequesterConsultantId(requesterConsultantId);
      task.setAttemptCount(0);
      task.setLastAttemptAt(null);
      task.setCreateDate(LocalDateTime.now(clock));
      task.setGeneration(task.getGeneration() + 1);
    } else {
      task =
          CaseHandoverMatrixRepairTask.builder()
              .action(CaseHandoverMatrixRepairAction.POWER_RAISE)
              .roomId(roomId)
              .memberId(memberId)
              .sessionId(sessionId)
              .requesterConsultantId(requesterConsultantId)
              .createDate(LocalDateTime.now(clock))
              .build();
    }
    task = repository.saveAndFlush(task);
    return new StagedRepair(task.getId(), task.getGeneration());
  }

  /** Removes a staged repair after its raise succeeded; a newer generation is left untouched. */
  public void completeStagedRepair(StagedRepair staged) {
    if (staged != null && staged.id() != null) {
      recordResult(staged.id(), staged.generation(), true);
    }
  }

  /** Restore a pre-existing member only when their grant transaction actually rolls back. */
  public void registerPowerRollback(
      String roomId,
      String memberId,
      Long sessionId,
      String requesterConsultantId,
      int previousPower,
      long ownershipRevision,
      UUID operationId) {
    if (previousPower == -1 || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    var repair =
        CaseHandoverMatrixRepairTask.builder()
            .action(CaseHandoverMatrixRepairAction.POWER_RESTORE)
            .roomId(roomId)
            .memberId(memberId)
            .sessionId(sessionId)
            .requesterConsultantId(requesterConsultantId)
            .previousPowerLevel(previousPower)
            .ownershipRevision(ownershipRevision)
            .attemptOperationId(operationId)
            // The grant table stores whole-second DATETIME: equality conservatively supersedes us.
            .createDate(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS))
            .build();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
              return;
            }
            boolean restored;
            try {
              restored = restorePowerUnlessSuperseded(repair);
            } catch (RuntimeException failure) {
              log.warn(
                  "Rolled-back Case Handover power restore failed ({})",
                  failure.getClass().getSimpleName());
              restored = false;
            }
            if (!restored) {
              enqueue(
                  repair.getAction(),
                  roomId,
                  memberId,
                  null,
                  sessionId,
                  requesterConsultantId,
                  previousPower,
                  ownershipRevision,
                  repair.getCreateDate(),
                  operationId);
              log.error(
                  "Rolled-back Case Handover left read-only power for session {}; durable repair queued",
                  sessionId);
            }
          }
        });
  }

  private boolean restorePowerUnlessSuperseded(CaseHandoverMatrixRepairTask task) {
    if (task.getPreviousPowerLevel() == null || task.getOwnershipRevision() == null) {
      return false;
    }
    // Grant/objection/takeover all hold this session lock. Keep it through the power write so a
    // delayed rollback repair cannot restore write access over a newer read-only grant.
    return Boolean.TRUE.equals(
        inNewTransaction(
            () -> {
              var session = sessionRepository.findByIdForUpdate(task.getSessionId());
              if (session.isEmpty()
                  || session.get().getOwnershipRevision() != task.getOwnershipRevision()
                  || hasSupersedingCoAccess(task)) {
                return true;
              }
              var owner = session.get().getConsultant();
              if (owner == null || isBlank(owner.getMatrixUserId())) {
                return false;
              }
              String token = matrixSynapseService.loginAsUserAccessToken(owner.getMatrixUserId());
              return !isBlank(token)
                  && matrixSynapseService.restoreUserPowerLevel(
                      task.getRoomId(),
                      task.getMemberId(),
                      -1,
                      task.getPreviousPowerLevel(),
                      token);
            }));
  }

  private void enqueue(
      CaseHandoverMatrixRepairAction action,
      String roomId,
      String memberId,
      String operatorId,
      Long sessionId,
      String requesterConsultantId) {
    enqueue(
        action,
        roomId,
        memberId,
        operatorId,
        sessionId,
        requesterConsultantId,
        null,
        null,
        null,
        null);
  }

  private void enqueue(
      CaseHandoverMatrixRepairAction action,
      String roomId,
      String memberId,
      String operatorId,
      Long sessionId,
      String requesterConsultantId,
      Integer previousPower,
      Long ownershipRevision,
      LocalDateTime attemptAt,
      UUID operationId) {
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
                    .previousPowerLevel(previousPower)
                    .ownershipRevision(ownershipRevision)
                    .attemptOperationId(operationId)
                    .createDate(attemptAt == null ? LocalDateTime.now(clock) : attemptAt)
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
                                existing.setPreviousPowerLevel(previousPower);
                                existing.setOwnershipRevision(ownershipRevision);
                                existing.setAttemptOperationId(operationId);
                                existing.setAttemptCount(0);
                                existing.setLastAttemptAt(null);
                                existing.setCreateDate(
                                    attemptAt == null ? LocalDateTime.now(clock) : attemptAt);
                                existing.setGeneration(existing.getGeneration() + 1);
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
    long generation = task.get().getGeneration();
    boolean repaired = attempt(task.get());
    recordResult(taskId, generation, repaired);
  }

  private boolean attempt(CaseHandoverMatrixRepairTask task) {
    try {
      if (task.getAction() == CaseHandoverMatrixRepairAction.POWER_RESTORE) {
        return restorePowerUnlessSuperseded(task);
      }
      if (task.getAction() == CaseHandoverMatrixRepairAction.POWER_RAISE) {
        return raisePowerWhileStillOwner(task);
      }
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
        case POWER_RESTORE ->
            throw new IllegalStateException("Power restore uses the session lock");
        case POWER_RAISE -> throw new IllegalStateException("Power raise is handled above");
      };
    } catch (RuntimeException exception) {
      log.warn(
          "Case Handover Matrix repair task {} failed with {}",
          task.getId(),
          exception.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * Ownership check and raise share {@link SessionOwnerLock} with the initial after-commit raise.
   */
  private boolean raisePowerWhileStillOwner(CaseHandoverMatrixRepairTask task) {
    return SessionOwnerLock.runWhileStillOwner(
        transactionManager,
        sessionRepository,
        task.getSessionId(),
        task.getRequesterConsultantId(),
        () -> matrixSynapseService.makeRoomAdmin(task.getRoomId(), task.getMemberId()));
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

  private boolean hasSupersedingCoAccess(CaseHandoverMatrixRepairTask task) {
    LocalDateTime now = LocalDateTime.now(clock);
    return handoverRequestRepository
        .findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(
            task.getSessionId(), task.getRequesterConsultantId())
        .stream()
        .filter(
            request ->
                request.getAccessType() == CaseHandoverRequest.AccessType.CO_ACCESS
                    || (request.getAccessType() == null
                        && CaseHandoverReasonCodes.ADVICE_REQUESTED.equals(
                            CaseHandoverReasonCodes.canonical(request.getReasonCode()))))
        .filter(
            request ->
                request.getStatus() == CaseHandoverRequest.Status.GRANTED
                    || request.getStatus()
                        == CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT
                    || request.getStatus() == CaseHandoverRequest.Status.EXPIRED
                    || (request.getStatus() == CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED
                        && (request.getExpiresAt() != null
                            || request.getMatrixMembershipAdded() != null)))
        .anyMatch(
            request ->
                ((request.getStatus() == CaseHandoverRequest.Status.GRANTED
                            || request.getStatus()
                                == CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT)
                        && (request.getExpiresAt() == null || request.getExpiresAt().isAfter(now)))
                    || (task.getAttemptOperationId() != null
                        && task.getAttemptOperationId().equals(request.getOperationId()))
                    || atOrAfter(request.getCreatedAt(), task.getCreateDate())
                    || atOrAfter(request.getResolvedAt(), task.getCreateDate()));
  }

  private boolean atOrAfter(LocalDateTime eventAt, LocalDateTime attemptAt) {
    return eventAt != null && attemptAt != null && !eventAt.isBefore(attemptAt);
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

  public void recordResult(Long taskId, long generation, boolean repaired) {
    inNewTransaction(
        () -> {
          repository
              .findByIdForUpdate(taskId)
              .filter(task -> task.getGeneration() == generation)
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
