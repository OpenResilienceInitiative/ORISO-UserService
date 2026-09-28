package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Retries a committed moderation decision until Matrix and the participant row agree. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatAdmissionProcessor {

  private final GroupChatJoinRequestRepository requests;
  private final GroupChatParticipantRepository participants;
  private final ChatRepository chats;
  private final ConsultantRepository consultants;
  private final GroupChatMembershipService membership;
  private final GroupChatAdmissionMatrixRepairService repair;

  @Value("${group.chat.admission.retry-backoff:PT1M}")
  private Duration retryBackoff;

  @Transactional(readOnly = true)
  public List<Long> pendingIds() {
    return requests
        .findAdmissionsReady(
            Status.ADMITTING,
            CustomLocalDateTime.nowInUtc().minus(retryBackoff),
            PageRequest.of(0, 20))
        .stream()
        .map(request -> request.getId())
        .toList();
  }

  /** Runs in its own transaction, after the admission intent has committed. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void process(Long requestId) {
    var pending = requests.findByIdForUpdate(requestId);
    if (pending.isEmpty()) {
      return;
    }
    var request = pending.get();
    if (request.getStatus() != Status.ADMITTING || request.getAdmissionRequestedAt() == null) {
      return;
    }

    var series = chats.findById(request.getSeriesId());
    var consultant = consultants.findByIdAndDeleteDateIsNull(request.getConsultantId());
    if (series.isEmpty()
        || ChatConverter.conversationTypeOf(series.get()) != ConversationType.SELF_HELP
        || consultant.isEmpty()
        || request.getAdmittedRole() == null) {
      recordRetry(requestId, request);
      return;
    }

    var current = participants.findBySeriesIdForUpdate(request.getSeriesId());
    var sessionId =
        current.stream().map(GroupChatParticipant::getChatId).filter(Objects::nonNull).findFirst();
    if (sessionId.isEmpty()) {
      recordRetry(requestId, request);
      return;
    }

    var matrixUserId = consultant.get().getMatrixUserId();
    var wasMemberBefore = membership.isMemberInRoom(series.get(), matrixUserId);
    if (wasMemberBefore.isEmpty()) {
      recordRetry(requestId, request);
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Group admission requires a transaction before Matrix join");
    }
    var roomId = membership.resolveMatrixRoomId(series.get());
    if (!wasMemberBefore.get()) {
      repair.recordBeforeJoin(
          requestId, request.getSeriesId(), request.getConsultantId(), roomId, matrixUserId);
    }
    if (!membership.addMemberToRoom(series.get(), matrixUserId)) {
      recordRetry(requestId, request);
      return;
    }
    if (!wasMemberBefore.get()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                membership.removeMemberFromRoom(roomId, matrixUserId);
              }
            }
          });
    }

    if (current.stream()
        .noneMatch(
            participant -> request.getConsultantId().equals(participant.getConsultantId()))) {
      participants.save(
          GroupChatParticipant.builder()
              .chatId(sessionId.get())
              .seriesId(request.getSeriesId())
              .consultantId(request.getConsultantId())
              .role(request.getAdmittedRole())
              .build());
    }
    request.setStatus(Status.ADMITTED);
    request.setDecidedAt(CustomLocalDateTime.nowInUtc());
    requests.save(request);
    repair.clearAfterAdmission(requestId);
    log.info("Group chat join request {} admitted after Matrix join", requestId);
  }

  private void recordRetry(
      Long requestId, de.caritas.cob.userservice.api.model.GroupChatJoinRequest request) {
    request.setAdmissionAttemptCount(request.getAdmissionAttemptCount() + 1);
    request.setAdmissionLastAttemptAt(CustomLocalDateTime.nowInUtc());
    requests.save(request);
    log.warn(
        "Group chat admission {} remains pending after attempt {}",
        requestId,
        request.getAdmissionAttemptCount());
  }

  /** Records a failed transaction in a fresh transaction so one bad row cannot starve the queue. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(Long requestId) {
    requests
        .findByIdForUpdate(requestId)
        .filter(request -> request.getStatus() == Status.ADMITTING)
        .ifPresent(request -> recordRetry(requestId, request));
  }
}
