package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomRetirement;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.AgencyEnquiryRoomRetirementRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.OrisoRoomRegistry;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService.OwnershipChange;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgencyEnquiryRoomRetirements {

  private final SessionRepository sessions;
  private final AgencyEnquiryRoomRetirementRepository retirements;
  private final OrisoRoomRegistry roomRegistry;

  public static boolean isInitialHoldingRoom(Session session) {
    var kind = session.getConversationType();
    return session.getAgencyId() != null
        && session.getTenantId() != null
        && (kind == null
            || kind == ConversationType.AGENCY_COUNSELLING
            || kind == ConversationType.LIVE_CHAT)
        && AgencyOpenEnquiryMembership.isOpenToDepartment(session, session.getAgencyId());
  }

  /** Records an initial holding-room retirement in the transaction that binds its replacement. */
  @Transactional
  public void replaceRoom(Session accepted, OwnershipChange assignment, String replacementRoomId) {
    var previousRoomId = accepted.getMatrixRoomId();
    var agencyId = accepted.getAgencyId();
    var tenantId = accepted.getTenantId();
    var current = sessions.lockFresh(accepted.getId()).orElseThrow(this::bindingChanged);
    if (isBlank(previousRoomId)
        || isBlank(replacementRoomId)
        || previousRoomId.equals(replacementRoomId)
        || current.getStatus() != Session.SessionStatus.IN_PROGRESS
        || current.getConsultant() == null
        || !current.getConsultant().getId().equals(assignment.ownerId())
        || !current.getId().equals(assignment.sessionId())
        || current.getOwnershipRevision() != assignment.revision()
        || current.getAgencyId() == null
        || current.getTenantId() == null
        || !Objects.equals(current.getAgencyId(), agencyId)
        || !Objects.equals(current.getTenantId(), tenantId)
        || !previousRoomId.equals(current.getMatrixRoomId())) {
      throw bindingChanged();
    }

    current.setMatrixRoomId(replacementRoomId);
    retirements.saveAndFlush(
        new AgencyEnquiryRoomRetirement(
            previousRoomId,
            current.getId(),
            current.getAgencyId(),
            current.getTenantId(),
            replacementRoomId));
    accepted.setMatrixRoomId(current.getMatrixRoomId());
    accepted.setRowVersion(current.getRowVersion());
  }

  boolean provesRetirement(AgencyEnquiryRevocationTask task, Session current) {
    return !isBlank(current.getMatrixRoomId())
        && retirements.existsByRoomIdAndSessionIdAndAgencyIdAndTenantIdAndReplacementRoomId(
            task.getRoomId(),
            task.getSessionId(),
            task.getAgencyId(),
            current.getTenantId(),
            current.getMatrixRoomId())
        && !roomRegistry.isKnownConversationRoom(task.getRoomId());
  }

  private ConflictException bindingChanged() {
    return new ConflictException("Enquiry room binding changed during acceptance");
  }
}
