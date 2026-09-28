package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.model.GroupChatAdmissionMatrixRepairTask;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.port.out.GroupChatAdmissionMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles Matrix joins that outlived a rolled-back admission database transaction. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatAdmissionMatrixRepairService {

  private final GroupChatAdmissionMatrixRepairTaskRepository tasks;
  private final GroupChatJoinRequestRepository requests;
  private final GroupChatParticipantRepository participants;
  private final GroupChatMembershipService membership;
  private final PlatformTransactionManager transactionManager;

  @Value("${group.chat.admission.matrix-repair.retry-backoff:PT1M}")
  private Duration retryBackoff;

  /** Commits independently before Matrix I/O so a later rollback cannot erase this intent. */
  public void recordBeforeJoin(
      Long requestId, Long seriesId, String consultantId, String roomId, String memberId) {
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.executeWithoutResult(
        status -> {
          if (tasks.findByRequestIdAndRoomId(requestId, roomId).isEmpty()) {
            tasks.saveAndFlush(
                GroupChatAdmissionMatrixRepairTask.builder()
                    .requestId(requestId)
                    .seriesId(seriesId)
                    .consultantId(consultantId)
                    .roomId(roomId)
                    .memberId(memberId)
                    .createdAt(CustomLocalDateTime.nowInUtc())
                    .build());
          }
        });
  }

  /** Called inside the admission transaction; deletion rolls back if the admission does. */
  public void clearAfterAdmission(Long requestId) {
    tasks.deleteByRequestId(requestId);
  }

  @Transactional(readOnly = true)
  public List<Long> readyIds() {
    return tasks
        .findReady(CustomLocalDateTime.nowInUtc().minus(retryBackoff), PageRequest.of(0, 20))
        .stream()
        .map(GroupChatAdmissionMatrixRepairTask::getId)
        .toList();
  }

  /** Locks the admission first, matching the writer lock order, before inspecting Matrix. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reconcile(Long taskId) {
    var snapshot = tasks.findById(taskId);
    if (snapshot.isEmpty()) {
      return;
    }
    var request = requests.findByIdForUpdate(snapshot.get().getRequestId());
    var task = tasks.findByIdForUpdate(taskId);
    if (task.isEmpty()) {
      return;
    }
    if (request.isEmpty() || request.get().getStatus() != Status.ADMITTING) {
      if (request.isPresent() && request.get().getStatus() == Status.ADMITTED) {
        tasks.delete(task.get());
      } else {
        recordRetry(task.get());
      }
      return;
    }
    if (participants
        .findBySeriesIdAndConsultantId(task.get().getSeriesId(), task.get().getConsultantId())
        .isPresent()) {
      tasks.delete(task.get());
      return;
    }

    var member = membership.isMemberInRoom(task.get().getRoomId(), task.get().getMemberId());
    if (member.isEmpty()) {
      recordRetry(task.get());
      return;
    }
    if (member.get()) {
      membership.removeMemberFromRoom(task.get().getRoomId(), task.get().getMemberId());
      member = membership.isMemberInRoom(task.get().getRoomId(), task.get().getMemberId());
    }
    if (member.isPresent() && !member.get()) {
      tasks.delete(task.get());
    } else {
      recordRetry(task.get());
    }
  }

  private void recordRetry(GroupChatAdmissionMatrixRepairTask task) {
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastAttemptAt(CustomLocalDateTime.nowInUtc());
    tasks.save(task);
    log.warn("Group admission Matrix repair {} remains pending", task.getId());
  }
}
