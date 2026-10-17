package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Takes everything a participant of a members-only chat holds besides the participation itself, in
 * one place, whenever they leave or are removed (remove, leave, owner edit):
 *
 * <ul>
 *   <li>the technical session of the chat, when it is assigned to them, goes to a remaining
 *       participant, an owner first;
 *   <li>their active supervision of that session ends;
 *   <li>after the transaction has committed, they leave the supervision side room, which is a
 *       separate Matrix room the group's own leave does not touch.
 * </ul>
 *
 * <p>{@link MembersOnlyChatAccess} already refuses a counsellor who is not a participant, so none
 * of these rows grants anything on its own any more; they are ended so that no list, notification
 * or Matrix room still points at someone who left. The Matrix leave is best effort, like the leave
 * of the group's room itself: a failure is logged and does not undo the removal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembersOnlyRevocation {

  private final SessionRepository sessionRepository;
  private final ConsultantRepository consultantRepository;
  private final GroupChatParticipantRepository participantRepository;
  private final SessionSupervisorRepository supervisorRepository;
  private final GroupChatMembershipService membershipService;

  /**
   * Call before the participation of {@code leaving} is deleted, in the same transaction. Does
   * nothing for an ordinary chat.
   */
  public void revokeAccessOf(Chat series, GroupChatParticipant leaving) {
    if (series == null
        || !series.isMembersOnly()
        || leaving == null
        || leaving.getChatId() == null) {
      return;
    }
    TenantContext.supplyAcrossTenants(
        () -> {
          endSupervisionBy(leaving);
          handOverSessionFrom(series, leaving);
          return null;
        });
  }

  private void handOverSessionFrom(Chat series, GroupChatParticipant leaving) {
    var session = sessionRepository.findById(leaving.getChatId()).orElse(null);
    if (session == null || !session.isAdvisedBy(leaving.getConsultantId())) {
      return;
    }
    participantRepository.findBySeriesId(series.getId()).stream()
        .filter(member -> !leaving.getConsultantId().equals(member.getConsultantId()))
        .sorted(Comparator.comparing(member -> member.getRole() == ParticipantRole.OWNER ? 0 : 1))
        .map(member -> consultantRepository.findByIdAndDeleteDateIsNull(member.getConsultantId()))
        .flatMap(Optional::stream)
        .findFirst()
        .ifPresent(
            successor -> {
              session.setConsultant(successor);
              sessionRepository.save(session);
            });
  }

  /**
   * A participant who was added as supervisor of the session reads it through that relation as
   * well; it ends with the participation.
   */
  private void endSupervisionBy(GroupChatParticipant leaving) {
    supervisorRepository
        .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(
            leaving.getChatId(), leaving.getConsultantId())
        .ifPresent(
            supervision -> {
              supervision.setIsActive(false);
              supervision.setRemovedDate(LocalDateTime.now());
              supervisorRepository.save(supervision);
              leaveSideRoomAfterCommit(supervision);
            });
  }

  private void leaveSideRoomAfterCommit(SessionSupervisor supervision) {
    var sideRoomId = supervision.getMatrixRoomId();
    var supervisor = supervision.getSupervisorConsultant();
    if (sideRoomId == null || sideRoomId.isBlank() || supervisor == null) {
      return;
    }
    var matrixUserId = supervisor.getMatrixUserId();
    Runnable leave =
        () -> {
          try {
            membershipService.removeMemberFromRoom(sideRoomId, matrixUserId);
          } catch (RuntimeException failure) {
            log.warn(
                "A former participant could not leave the supervision side room: {}",
                failure.getClass().getSimpleName());
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      leave.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            leave.run();
          }
        });
  }
}
