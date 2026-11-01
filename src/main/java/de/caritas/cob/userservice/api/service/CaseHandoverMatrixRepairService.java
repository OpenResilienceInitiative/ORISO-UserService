package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverMatrixRepairTask;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.port.out.CaseHandoverMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
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
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;
  private final @NonNull Clock clock;
  private final @NonNull PlatformTransactionManager transactionManager;

  /** Not a consultant id: a supervision kick is never cancelled by a Case Handover grant. */
  public static final String SUPERVISION_REMOVAL_REQUESTER = "supervision-removal";

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

  /**
   * Queues the removal of a former supervisor from a Matrix room through the caller's own
   * transaction (no new one): the kick is queued exactly when the supervision ends, and not at all
   * when that transaction rolls back.
   */
  public void enqueueRemovalInCurrentTransaction(
      String roomId, String memberId, String operatorId, Long sessionId) {
    repository
        .findExistingForUpdate(CaseHandoverMatrixRepairAction.REMOVE, roomId, memberId)
        .ifPresentOrElse(
            existing -> {
              existing.setOperatorId(operatorId);
              existing.setSessionId(sessionId);
              existing.setRequesterConsultantId(SUPERVISION_REMOVAL_REQUESTER);
              existing.setAttemptCount(0);
              existing.setLastAttemptAt(null);
              existing.setCreateDate(LocalDateTime.now(clock));
              existing.setGeneration(existing.getGeneration() + 1);
              repository.save(existing);
            },
            () ->
                repository.save(
                    CaseHandoverMatrixRepairTask.builder()
                        .action(CaseHandoverMatrixRepairAction.REMOVE)
                        .roomId(roomId)
                        .memberId(memberId)
                        .sessionId(sessionId)
                        .requesterConsultantId(SUPERVISION_REMOVAL_REQUESTER)
                        .operatorId(operatorId)
                        .createDate(LocalDateTime.now(clock))
                        .build()));
  }

  /**
   * Drops the queued kicks of a supervisor in a session, in the caller's transaction: a supervisor
   * who is added again must not be removed by a retry that was queued for their earlier removal.
   */
  public void cancelPendingRemovals(Long sessionId, String memberId) {
    repository.deleteByActionAndRequesterConsultantIdAndSessionIdAndMemberId(
        CaseHandoverMatrixRepairAction.REMOVE, SUPERVISION_REMOVAL_REQUESTER, sessionId, memberId);
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
      boolean activeAccess = hasActiveAccess(task);
      if (task.getAction() == CaseHandoverMatrixRepairAction.REMOVE
          && (activeAccess || supervisorIsActiveAgain(task))) {
        return true;
      }
      String loginId =
          task.getAction() == CaseHandoverMatrixRepairAction.JOIN
              ? task.getMemberId()
              : task.getOperatorId();
      String token = isBlank(loginId) ? null : matrixSynapseService.loginAsUserAccessToken(loginId);
      if (isBlank(token)) {
        // An operator who can no longer mint a token (e.g. a disabled consultant) must not block a
        // supervision removal: the supervisor can still leave with their own token.
        return task.getAction() == CaseHandoverMatrixRepairAction.REMOVE
            && SUPERVISION_REMOVAL_REQUESTER.equals(task.getRequesterConsultantId())
            && removeOrConfirmAbsent(task, null);
      }
      // Re-checked after the login round trip: never kick on the strength of a stale check.
      if (task.getAction() == CaseHandoverMatrixRepairAction.REMOVE
          && supervisorIsActiveAgain(task)) {
        return true;
      }
      return switch (task.getAction()) {
        case JOIN -> reconcileJoin(task, token, activeAccess);
        case REMOVE -> removeThenRestoreIfReAdded(task, token);
        case POWER_RESTORE ->
            throw new IllegalStateException("Power restore uses the session lock");
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

  /**
   * A queued supervision kick is void once the supervisor is authorised again: the active
   * supervision row is the truth, not the task that was queued for an earlier removal.
   */
  private boolean supervisorIsActiveAgain(CaseHandoverMatrixRepairTask task) {
    return SUPERVISION_REMOVAL_REQUESTER.equals(task.getRequesterConsultantId())
        && sessionSupervisorRepository
            .existsBySessionIdAndIsActiveTrueAndSupervisorConsultantMatrixUserId(
                task.getSessionId(), task.getMemberId());
  }

  /**
   * A re-add can commit between the last active-supervision check and the remote kick. After the
   * kick the database is therefore checked once more; if the supervisor is active again they are
   * invited and joined again, using the same steps as the add path.
   */
  private boolean removeThenRestoreIfReAdded(CaseHandoverMatrixRepairTask task, String token) {
    if (!removeOrConfirmAbsent(task, token)) {
      return false;
    }
    if (!supervisorIsActiveAgain(task)) {
      return true;
    }
    log.info(
        "Supervisor of session {} was re-added during removal from room {}; restoring membership",
        task.getSessionId(),
        task.getRoomId());
    try {
      if (!isBlank(token)) {
        try {
          matrixSynapseService.inviteUserToRoom(task.getRoomId(), task.getMemberId(), token);
        } catch (Exception exception) {
          // "already in the room" or a failed invite: joining below decides.
          log.debug("Re-invite of supervisor skipped: {}", exception.getClass().getSimpleName());
        }
      }
      String memberToken = matrixSynapseService.loginAsUserAccessToken(task.getMemberId());
      return !isBlank(memberToken) && matrixSynapseService.joinRoom(task.getRoomId(), memberToken);
    } catch (RuntimeException exception) {
      log.warn(
          "Restoring re-added supervisor in room {} failed with {}",
          task.getRoomId(),
          exception.getClass().getSimpleName());
      return false;
    }
  }

  private boolean removeOrConfirmAbsent(CaseHandoverMatrixRepairTask task, String token) {
    if (!isBlank(token)
        && matrixSynapseService.removeUserFromRoom(task.getRoomId(), task.getMemberId(), token)) {
      return true;
    }
    if (isAbsent(task)) {
      return true;
    }
    // The operator may lack the power to kick in a private room. A removed supervisor can always
    // leave with their own token, which ends the membership just the same.
    if (SUPERVISION_REMOVAL_REQUESTER.equals(task.getRequesterConsultantId())) {
      String memberToken = matrixSynapseService.loginAsUserAccessToken(task.getMemberId());
      if (!isBlank(memberToken)
          && !supervisorIsActiveAgain(task)
          && matrixSynapseService.leaveRoom(task.getRoomId(), memberToken)) {
        return true;
      }
      return isAbsent(task);
    }
    return false;
  }

  private boolean isAbsent(CaseHandoverMatrixRepairTask task) {
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
