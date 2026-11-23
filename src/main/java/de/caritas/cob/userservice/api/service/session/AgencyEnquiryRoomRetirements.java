package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRevocationTask;
import de.caritas.cob.userservice.api.model.AgencyEnquiryRoomDecision.Outcome;
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
  private final AgencyEnquiryRoomDecisions roomDecisions;
  private final SessionOwnershipService ownership;

  public static boolean isInitialHoldingRoom(Session session) {
    var kind = session.getConversationType();
    return session.getAgencyId() != null
        && session.getTenantId() != null
        && (kind == null
            || kind == ConversationType.AGENCY_COUNSELLING
            || kind == ConversationType.LIVE_CHAT)
        && AgencyOpenEnquiryMembership.isOpenToDepartment(session, session.getAgencyId());
  }

  /** Start external work only while the same committed acceptance remains current. */
  @Transactional(noRollbackFor = ReopenedPreparationConflict.class)
  public void startExternalWork(Session accepted, OwnershipChange assignment) {
    if (AgencyEnquiryRoomDecisions.tracksRegistration(accepted)
        && !ownership.startEnquiryRoomWork(lockAcceptance(accepted, assignment))) {
      // Commit the reopened case and released work while the request reports the original conflict.
      throw new ReopenedPreparationConflict();
    }
  }

  private static final class ReopenedPreparationConflict extends ConflictException {
    private static final long serialVersionUID = 1L;

    private ReopenedPreparationConflict() {
      super("Enquiry room preparation is no longer current");
    }
  }

  /** Records an initial holding-room retirement in the transaction that binds its replacement. */
  @Transactional
  public void replaceRoom(Session accepted, OwnershipChange assignment, String replacementRoomId) {
    var previousRoomId = accepted.getMatrixRoomId();
    if (isBlank(replacementRoomId) || replacementRoomId.equals(previousRoomId)) {
      throw bindingChanged();
    }
    var current = lockAcceptance(accepted, assignment);
    if (AgencyEnquiryRoomDecisions.tracksRegistration(current)) {
      roomDecisions.resolve(current, Outcome.REPLACED);
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

  /** Settles the original binding after Matrix confirms room reuse. */
  @Transactional
  public void reuseRoom(Session accepted, OwnershipChange assignment) {
    if (AgencyEnquiryRoomDecisions.tracksRegistration(accepted)) {
      roomDecisions.resolve(lockAcceptance(accepted, assignment), Outcome.REUSED);
    }
  }

  private Session lockAcceptance(Session accepted, OwnershipChange assignment) {
    var room = accepted.getMatrixRoomId();
    var current = sessions.lockFresh(accepted.getId()).orElseThrow(this::bindingChanged);
    if (isBlank(room)
        || current.getStatus() != Session.SessionStatus.IN_PROGRESS
        || current.getConsultant() == null
        || !current.getConsultant().getId().equals(assignment.ownerId())
        || !current.getId().equals(assignment.sessionId())
        || current.getOwnershipRevision() != assignment.revision()
        || current.getAgencyId() == null
        || current.getTenantId() == null
        || !Objects.equals(current.getAgencyId(), accepted.getAgencyId())
        || !Objects.equals(current.getTenantId(), accepted.getTenantId())
        || !room.equals(current.getMatrixRoomId())) {
      throw bindingChanged();
    }
    return current;
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
