package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatMatrixCleanupTask;
import de.caritas.cob.userservice.api.model.GroupChatMatrixCleanupTask.Action;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatMatrixCleanupTaskRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Journals only newly created group rooms or new remote joins, never pre-existing access. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatMatrixCleanupService {
  private final GroupChatMatrixCleanupTaskRepository tasks;
  private final ConsultantRepository consultants;
  private final ChatRepository chats;
  private final GroupChatParticipantRepository participants;
  private final MatrixSynapseService matrix;
  private final GroupChatMembershipService membership;
  private final Clock clock;

  @Value("${group.chat.matrix-cleanup.retry-backoff:PT1M}")
  private Duration retryBackoff;

  /** Writers and retry workers acquire this stable existing row before any chat/task lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOwner(Consultant expected) {
    var current =
        consultants
            .findPictureOwnerForUpdate(expected.getId())
            .orElseThrow(() -> new ConflictException("Group owner no longer exists"));
    if (!Objects.equals(current.getTenantId(), expected.getTenantId()))
      throw new ConflictException("Group owner changed tenant");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lockGroup(Chat expected) {
    lockOwner(expected.getChatOwner());
    var current =
        chats
            .findByIdForUpdate(expected.getId())
            .orElseThrow(() -> new ConflictException("Group no longer exists"));
    if (!Objects.equals(current.getChatOwner().getId(), expected.getChatOwner().getId())
        || !Objects.equals(current.getMatrixRoomId(), expected.getMatrixRoomId()))
      throw new ConflictException("Group room ownership changed");
  }

  /** Immutable ownership scalars extracted before suspending the caller's transaction. */
  public record GroupOwner(Long seriesId, String ownerId, Long tenantId) {}

  // No entity/proxy access or owner/chat lookup here: the suspended writer holds those locks.
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long recordRoom(GroupOwner owner, String roomId) {
    return record(Action.PURGE_ROOM, owner, null, roomId, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long recordJoin(GroupOwner owner, String consultantId, String roomId, String memberId) {
    return record(Action.REMOVE_MEMBER, owner, consultantId, roomId, memberId);
  }

  private Long record(
      Action action, GroupOwner owner, String consultantId, String roomId, String memberId) {
    return tasks
        .saveAndFlush(
            GroupChatMatrixCleanupTask.builder()
                .action(action)
                .seriesId(owner.seriesId())
                .ownerId(owner.ownerId())
                .tenantId(owner.tenantId())
                .consultantId(consultantId)
                .roomId(roomId)
                .memberId(memberId)
                .createdAt(LocalDateTime.now(clock))
                .build())
        .getId();
  }

  /** Deletions participate in the writer: rollback restores independently committed intents. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clear(Long id) {
    tasks.deleteById(id);
  }

  /** Caller still owns the operation lock. Failure leaves the committed intent for retry. */
  public void compensate(Long id) {
    if (id == null) return;
    try {
      tasks
          .findById(id)
          .ifPresent(
              task -> {
                if (attempt(task)) tasks.delete(task);
              });
    } catch (RuntimeException failure) {
      log.warn(
          "Group Matrix cleanup {} remains pending ({})", id, failure.getClass().getSimpleName());
    }
  }

  public boolean purgeUnjournaledRoom(String roomId) {
    var outcome = matrix.purgeRoomOrConfirmGone(roomId);
    return outcome != null && outcome != MatrixSynapseService.RoomPurgeOutcome.FAILED;
  }

  @Transactional(readOnly = true)
  public List<Long> readyIds() {
    return tasks
        .findReady(LocalDateTime.now(clock).minus(retryBackoff), PageRequest.of(0, 20))
        .stream()
        .map(GroupChatMatrixCleanupTask::getId)
        .toList();
  }

  // The initial task read may establish a repeatable-read snapshot before the owner-lock wait.
  // Both destructive guards below use current locking reads to see later committed adoption.
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retry(Long id) {
    var snapshot = tasks.findById(id);
    if (snapshot.isEmpty()) return;
    // Same lock order as creation/reconciliation. No concurrent writer can be mistaken for
    // rollback.
    var owner = consultants.findPictureOwnerForUpdate(snapshot.get().getOwnerId());
    var series = chats.findByIdForUpdate(snapshot.get().getSeriesId());
    var task = tasks.findByIdForUpdate(id);
    if (task.isEmpty()) return;
    var pending = task.get();
    if (owner.isPresent() && !Objects.equals(owner.get().getTenantId(), pending.getTenantId())) {
      defer(pending);
      return;
    }
    if (series.isPresent()
        && (!Objects.equals(series.get().getChatOwner().getId(), pending.getOwnerId())
            || !Objects.equals(series.get().getChatOwner().getTenantId(), pending.getTenantId()))) {
      defer(pending);
      return;
    }
    var roomOwner = chats.findByMatrixRoomIdForUpdate(pending.getRoomId());
    if (roomOwner.isPresent()) {
      if (!Objects.equals(roomOwner.get().getId(), pending.getSeriesId())) {
        defer(pending);
        return;
      }
      if (pending.getAction() == Action.PURGE_ROOM
          || participants
              .findBySeriesIdAndConsultantIdForUpdate(
                  pending.getSeriesId(), pending.getConsultantId())
              .isPresent()) {
        tasks.delete(pending);
        return; // Committed legitimate ownership/participation supersedes cleanup.
      }
    }
    try {
      if (attempt(pending)) {
        tasks.delete(pending);
        return;
      }
    } catch (RuntimeException failure) {
      log.warn("Group Matrix cleanup {} failed ({})", id, failure.getClass().getSimpleName());
    }
    defer(pending);
  }

  private boolean attempt(GroupChatMatrixCleanupTask task) {
    return task.getAction() == Action.PURGE_ROOM
        ? purgeUnjournaledRoom(task.getRoomId())
        : membership.removeMemberFromRoomAndConfirm(task.getRoomId(), task.getMemberId());
  }

  private void defer(GroupChatMatrixCleanupTask task) {
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastAttemptAt(LocalDateTime.now(clock));
    tasks.save(task);
  }
}
