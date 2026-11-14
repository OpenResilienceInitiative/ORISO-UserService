package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.model.SessionAppointment;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DirectInvitationMemberRepository;
import de.caritas.cob.userservice.api.port.out.SessionAppointmentRepository;
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
 * When a direct invitation with an appointment expires (ORISO-Frontend#2252, decision 9): the link
 * closes, guests (temporary accounts, interpreters included) leave the room, and a shared
 * appointment conversation is archived. The 1:1 an invitation was created from stays open; only its
 * invited guests leave.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationExpiryService {

  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull SessionAppointmentRepository appointments;
  private final @NonNull DirectInvitationMemberRepository members;
  private final @NonNull UserRepository users;
  private final @NonNull DirectInvitationRoomMembership rooms;
  private final @NonNull SessionArchiveService archive;

  public List<Long> dueIds(long afterId, int pageSize) {
    return links.findExpiredInvitationIds(
        DirectInvitationService.nowUtc(), afterId, PageRequest.of(0, pageSize));
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
    var session =
        appointments
            .findById(link.getAppointmentId())
            .map(SessionAppointment::getSession)
            .orElse(null);
    if (session == null) {
      return true;
    }
    for (var guest : members.findBySessionIdAndGuestTrueAndRemovedAtIsNull(session.getId())) {
      var matrixId = users.findById(guest.getUserId()).map(User::getMatrixUserId).orElse(null);
      if (!rooms.remove(session, matrixId)) {
        log.warn(
            "Guest of expired invitation {} may still be in the room of session {}",
            link.getId(),
            session.getId());
      }
      guest.setRemovedAt(DirectInvitationService.nowUtc());
      members.save(guest);
    }
    if (!link.isJoinsExistingSession()) {
      archive.archiveBySystem(session);
    }
    return true;
  }
}
