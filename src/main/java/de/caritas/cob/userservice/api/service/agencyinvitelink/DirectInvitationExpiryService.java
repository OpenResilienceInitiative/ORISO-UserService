package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.DirectInvitationMember;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a direct invitation with an appointment ends (ORISO-Frontend#2252, decision 9): at expiry,
 * and equally when its counsellor switches it off or deletes it. Guests (temporary accounts,
 * interpreters included) leave a shared appointment conversation, which is archived; everybody who
 * came in by link leaves the 1:1 an invitation was created from, which stays open.
 *
 * <p>Fail closed: a person counts as removed only once Matrix confirmed it. Until then the removal
 * stays due and the sweep retries it, also after the link itself was deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationExpiryService {

  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull SessionAppointmentRepository appointments;
  private final @NonNull DirectInvitationMemberRepository members;
  private final @NonNull SessionRepository sessions;
  private final @NonNull UserRepository users;
  private final @NonNull DirectInvitationRoomMembership rooms;
  private final @NonNull SessionArchiveService archive;

  public List<Long> dueIds(long afterId, int pageSize) {
    return links.findExpiredInvitationIds(
        DirectInvitationService.nowUtc(), afterId, PageRequest.of(0, pageSize));
  }

  public List<Long> pendingRemovalIds(long afterId, int pageSize) {
    return members.findPendingRevocationIds(afterId, PageRequest.of(0, pageSize));
  }

  /**
   * Closes one invitation under a row lock and only from ACTIVE, so competing replicas close it
   * once.
   *
   * @return whether this run closed it
   */
  @Transactional
  public boolean expire(Long linkId) {
    var link = links.findByIdForUpdate(linkId).orElse(null);
    if (link == null
        || !InviteLinkStatus.ACTIVE.name().equals(link.getStatus())
        || link.getAppointmentId() == null
        || link.getExpiresAt() == null
        || !link.getExpiresAt().isBefore(DirectInvitationService.nowUtc())) {
      return false;
    }
    link.setStatus(InviteLinkStatus.EXPIRED.name());
    link.setUpdateDate(DirectInvitationService.nowUtc());
    links.save(link);
    revoke(link);
    return true;
  }

  /**
   * The people of an invitation that ends lose access: at expiry, when it is switched off and
   * before it is deleted. A link without appointment has no shared conversation and is left alone.
   */
  @Transactional
  public void revoke(AgencyInviteLink link) {
    if (link.getAppointmentId() == null) {
      return;
    }
    var session =
        appointments
            .findById(link.getAppointmentId())
            .map(SessionAppointment::getSession)
            .orElse(null);
    if (session == null) {
      return;
    }
    var now = DirectInvitationService.nowUtc();
    for (var member : members.findByInviteLinkIdAndRemovedAtIsNull(link.getId())) {
      // Anyone who entered an existing 1:1 by link leaves it; accounts stay in a shared
      // appointment conversation, which is archived below.
      if (!link.isJoinsExistingSession() && !member.isGuest()) {
        continue;
      }
      if (member.getRevokeDueAt() == null) {
        member.setRevokeDueAt(now);
      }
      tryRemove(session, member);
      members.save(member);
    }
    if (!link.isJoinsExistingSession()) {
      archive.archiveBySystem(session);
    }
  }

  /**
   * One due removal Matrix has not confirmed yet.
   *
   * @return whether it is confirmed now
   */
  @Transactional
  public boolean retryRemoval(Long memberId) {
    var member = members.findById(memberId).orElse(null);
    if (member == null || member.getRemovedAt() != null || member.getRevokeDueAt() == null) {
      return false;
    }
    var session = sessions.findById(member.getSessionId()).orElse(null);
    if (session == null) {
      // The conversation is gone, and its room membership with it.
      member.setRemovedAt(DirectInvitationService.nowUtc());
      members.save(member);
      return true;
    }
    var removed = tryRemove(session, member);
    members.save(member);
    return removed;
  }

  private boolean tryRemove(Session session, DirectInvitationMember member) {
    var matrixId = users.findById(member.getUserId()).map(User::getMatrixUserId).orElse(null);
    // A person without a Matrix account (deleted meanwhile) is in no room any more.
    var removed = matrixId == null || rooms.remove(session, matrixId);
    if (removed) {
      member.setRemovedAt(DirectInvitationService.nowUtc());
    } else {
      log.warn(
          "Invited member {} of session {} is still in the room; the sweep tries again",
          member.getId(),
          session.getId());
    }
    return removed;
  }
}
