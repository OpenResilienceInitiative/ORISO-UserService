package de.caritas.cob.userservice.api.service.chat;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.actions.chat.MatrixChatShutdownService;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Takes everything a participant of a members-only chat holds besides the participation itself, in
 * one place, whenever they leave or are removed (remove, leave, owner edit):
 *
 * <ul>
 *   <li>the technical session of the chat, when it is assigned to them, goes to a remaining
 *       participant, an owner first;
 *   <li>their active supervision of that session ends, and they leave the supervision side room,
 *       which is a separate Matrix room the group's own leave does not touch.
 * </ul>
 *
 * <p>{@link MembersOnlyChatAccess} already refuses a counsellor who is not a participant, so none
 * of these rows grants anything on its own any more; they are ended so that no list, notification
 * or Matrix room still points at someone who left. The Matrix leaves are strict, like the leave of
 * the group's room itself: when one fails the removal answers 502 and rolls back, so no one is
 * revoked in the database yet still joined in an encrypted room.
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
  private final ChatRepository chatRepository;
  private final MatrixChatShutdownService shutdownService;

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

  /**
   * Call when a members-only chat is deleted, before its participations are, in the same
   * transaction: the technical session of the chat is closed and every supervision of it ends, so
   * nobody keeps an in-progress case that points at a room that no longer exists. Does nothing for
   * an ordinary chat.
   */
  public void retireCaseOf(Chat series) {
    if (series == null || !series.isMembersOnly()) {
      return;
    }
    TenantContext.supplyAcrossTenants(
        () -> {
          var sessionIds = new LinkedHashSet<Long>();
          participantRepository.findBySeriesId(series.getId()).stream()
              .map(GroupChatParticipant::getChatId)
              .filter(Objects::nonNull)
              .forEach(sessionIds::add);
          if (series.getMatrixRoomId() != null) {
            sessionRepository
                .findByMatrixRoomId(series.getMatrixRoomId())
                .map(Session::getId)
                .ifPresent(sessionIds::add);
          }
          sessionIds.stream()
              .map(sessionRepository::findById)
              .flatMap(Optional::stream)
              .forEach(this::close);
          return null;
        });
  }

  private void close(Session session) {
    supervisorRepository.findBySessionIdAndIsActiveTrue(session.getId()).forEach(this::end);
    session.setStatus(SessionStatus.DONE);
    session.setUpdateDate(LocalDateTime.now());
    sessionRepository.save(session);
  }

  private void handOverSessionFrom(Chat series, GroupChatParticipant leaving) {
    var session = sessionRepository.findById(leaving.getChatId()).orElse(null);
    if (session == null || !session.isAdvisedBy(leaving.getConsultantId())) {
      return;
    }
    successorOf(participantRepository.findBySeriesId(series.getId()), leaving.getConsultantId())
        .ifPresent(
            successor -> {
              session.setConsultant(successor);
              sessionRepository.save(session);
            });
  }

  /** The remaining active participant who takes a case over, an owner first. */
  private Optional<Consultant> successorOf(
      List<GroupChatParticipant> participants, String leavingConsultantId) {
    return participants.stream()
        .filter(member -> !leavingConsultantId.equals(member.getConsultantId()))
        .sorted(Comparator.comparing(member -> member.getRole() == ParticipantRole.OWNER ? 0 : 1))
        .map(member -> consultantRepository.findByIdAndDeleteDateIsNull(member.getConsultantId()))
        .flatMap(Optional::stream)
        .findFirst();
  }

  /**
   * Call when a consultant is marked for deletion, inside that transaction and while their chat
   * account still works: they leave every members-only chat they take part in exactly as if they
   * had left or been removed (supervision and side room first, the participation, the main room
   * last). A Matrix leave that fails answers 502 and rolls the whole deletion back, so a departing
   * counsellor never keeps reading private rooms until the account is finally deleted. Ordinary
   * chats are left alone.
   */
  public void revokeAllAccessOf(Consultant leaving) {
    if (leaving == null || leaving.getId() == null) {
      return;
    }
    var participations =
        TenantContext.supplyAcrossTenants(
            () -> participantRepository.findByConsultantId(leaving.getId()));
    for (var participation : participations) {
      var series =
          TenantContext.supplyAcrossTenants(
                  () -> chatRepository.findById(participation.getSeriesId()))
              .filter(Chat::isMembersOnly);
      if (series.isEmpty()) {
        continue;
      }
      var chat = series.get();
      revokeAccessOf(chat, participation);
      participantRepository.delete(participation);
      if (ownedBy(chat, participation) && !passOwnershipOn(chat, leaving.getId())) {
        // Nobody is left to own it: the chat is retired like a deleted one, the strict purge of
        // its room (which removes the leaving member as well) last.
        retire(chat);
        continue;
      }
      membershipService.removeParticipantFromRoom(chat, leaving.getMatrixUserId());
    }
  }

  /**
   * Call when an owner of a chat leaves or is deleted, in the same transaction: the remaining
   * active participant who takes the chat over (an owner, else a co-moderator, else the
   * longest-standing participant, the lowest id) becomes {@link Chat#getChatOwner()} when the
   * leaving consultant was it, and an OWNER when no owner is left. Database only, like a transfer
   * of primary ownership; Matrix power levels are not touched.
   *
   * @return {@code false} when nobody active remains to own the chat
   */
  public boolean passOwnershipOn(Chat chat, String leavingConsultantId) {
    var next = nextOwnerOf(chat, leavingConsultantId);
    if (next.isEmpty()) {
      return false;
    }
    var successor = next.get();
    TenantContext.supplyAcrossTenants(
        () -> {
          if (successor.participation().getRole() != ParticipantRole.OWNER) {
            successor.participation().setRole(ParticipantRole.OWNER);
            participantRepository.save(successor.participation());
          }
          var primary = chat.getChatOwner();
          if (primary == null || leavingConsultantId.equals(primary.getId())) {
            chat.setChatOwner(successor.account());
            chat.setUpdateDate(nowInUtc());
            chatRepository.save(chat);
          }
          return null;
        });
    return true;
  }

  private static boolean ownedBy(Chat chat, GroupChatParticipant participation) {
    return participation.getRole() == ParticipantRole.OWNER
        || (chat.getChatOwner() != null
            && participation.getConsultantId().equals(chat.getChatOwner().getId()));
  }

  /**
   * The remaining active participant who takes a chat over: an owner, else a co-moderator, else the
   * longest-standing participant (the lowest id). Same database-only change as a transfer of
   * primary ownership; Matrix power levels are not touched.
   */
  private Optional<OwnerCandidate> nextOwnerOf(Chat chat, String leavingConsultantId) {
    return TenantContext.supplyAcrossTenants(
        () ->
            participantRepository.findBySeriesId(chat.getId()).stream()
                .filter(member -> !leavingConsultantId.equals(member.getConsultantId()))
                .sorted(
                    Comparator.comparing((GroupChatParticipant member) -> rank(member.getRole()))
                        .thenComparing(
                            GroupChatParticipant::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                .flatMap(
                    member ->
                        consultantRepository
                            .findByIdAndDeleteDateIsNull(member.getConsultantId())
                            .map(account -> new OwnerCandidate(member, account))
                            .stream())
                .findFirst());
  }

  private static int rank(ParticipantRole role) {
    return role == ParticipantRole.OWNER ? 0 : role == ParticipantRole.CO_MODERATOR ? 1 : 2;
  }

  private void retire(Chat chat) {
    TenantContext.supplyAcrossTenants(
        () -> {
          retireCaseOf(chat);
          participantRepository.deleteBySeriesId(chat.getId());
          chatRepository.delete(chat);
          return null;
        });
    // Last, in the same transaction: a room that cannot be purged answers 502 and rolls the
    // deletion of the consultant back.
    shutdownService.shutdownRoom(chat);
  }

  private record OwnerCandidate(GroupChatParticipant participation, Consultant account) {}

  /**
   * Call when a consultant is deleted while still the assignee of the technical session of a
   * members-only chat: the case goes to a remaining active participant, an owner first, and is
   * retired (closed, its supervisions ended) when nobody active remains, instead of being deleted
   * under a chat that still exists.
   *
   * @return {@code false} when the session has no participants, so there is no chat to keep it for;
   *     the caller then treats it like any other session
   */
  public boolean keepCaseOnDeletionOf(Session session, Consultant leaving) {
    if (session == null || session.getId() == null || leaving == null) {
      return false;
    }
    return TenantContext.supplyAcrossTenants(
        () -> {
          var participants = participantRepository.findByChatId(session.getId());
          if (participants.isEmpty()) {
            return false;
          }
          var successor = successorOf(participants, leaving.getId());
          if (successor.isPresent()) {
            session.setConsultant(successor.get());
            sessionRepository.save(session);
          } else {
            close(session);
          }
          return true;
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
        .ifPresent(this::end);
  }

  private void end(SessionSupervisor supervision) {
    supervision.setIsActive(false);
    supervision.setRemovedDate(LocalDateTime.now());
    supervisorRepository.save(supervision);
    leaveSideRoom(supervision);
  }

  /**
   * Inside the transaction, strictly: a supervisor who stays in the side room keeps reading it
   * while the database says the supervision ended. A failed leave answers 502 and rolls the whole
   * removal back, so it can be repeated.
   */
  private void leaveSideRoom(SessionSupervisor supervision) {
    var sideRoomId = supervision.getMatrixRoomId();
    var supervisor = supervision.getSupervisorConsultant();
    if (sideRoomId == null || sideRoomId.isBlank() || supervisor == null) {
      return;
    }
    membershipService.removeMemberFromRoomOrFail(sideRoomId, supervisor.getMatrixUserId());
  }
}
