package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.TeamDiscussionRoomCleanupTask;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRoomCleanupTaskRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists and retries cleanup of losing Matrix rooms from concurrent first opens. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamDiscussionRoomCleanupService {

  private final @NonNull TeamDiscussionRoomCleanupTaskRepository cleanupTaskRepository;
  private final @NonNull TeamDiscussionRepository discussionRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull Clock clock;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailedCleanup(Long sessionId, String matrixRoomId) {
    cleanupTaskRepository.saveAndFlush(
        TeamDiscussionRoomCleanupTask.builder()
            .sessionId(sessionId)
            .matrixRoomId(matrixRoomId)
            .createDate(LocalDateTime.now(clock))
            .build());
  }

  /** Retries every durable task; the enclosing scheduler serializes this across replicas. */
  public void retryPendingCleanup() {
    cleanupTaskRepository.findAllByOrderByCreateDateAsc().forEach(this::retry);
  }

  private void retry(TeamDiscussionRoomCleanupTask task) {
    if (discussionRepository.findByMatrixRoomId(task.getMatrixRoomId()).isPresent()) {
      log.error(
          "Refusing to purge team discussion room {} because it is now persisted",
          task.getMatrixRoomId());
      cleanupTaskRepository.delete(task);
      return;
    }
    MatrixSynapseService.RoomPurgeOutcome outcome;
    try {
      outcome = matrixSynapseService.purgeRoomOrConfirmGone(task.getMatrixRoomId());
    } catch (RuntimeException exception) {
      log.warn(
          "Retrying team discussion room cleanup {} failed with {}",
          task.getMatrixRoomId(),
          exception.getClass().getSimpleName());
      outcome = MatrixSynapseService.RoomPurgeOutcome.FAILED;
    }
    if (outcome != MatrixSynapseService.RoomPurgeOutcome.FAILED) {
      cleanupTaskRepository.delete(task);
      return;
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastAttemptAt(LocalDateTime.now(clock));
    cleanupTaskRepository.save(task);
  }
}
