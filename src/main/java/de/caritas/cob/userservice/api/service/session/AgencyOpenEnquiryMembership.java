package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.JoinAttempt;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Grants department room access only while the persisted roster and enquiry permit it. */
@Service
@RequiredArgsConstructor
public class AgencyOpenEnquiryMembership {
  static final List<SessionStatus> OPEN_STATUSES =
      List.of(SessionStatus.NEW, SessionStatus.INITIAL);

  private final ConsultantRepository consultants;
  private final ConsultantAgencyRepository assignments;
  private final SessionRepository sessions;
  private final AgencySilentMembershipService matrixMembership;

  /** An empty result means the queued member is no longer entitled. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public Optional<JoinAttempt> joinIfCurrent(
      String consultantId, Long agencyId, Long sessionId, String roomId, String agencyToken) {
    // This is the same counsellor row locked by roster changes and durable revocation. Keep the
    // locks through one non-waiting Matrix attempt, and release them before any rate-limit sleep.
    var consultant = consultants.findActiveByIdForUpdate(consultantId);
    if (consultant.isEmpty()
        || !assignments.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            consultantId, agencyId)) {
      return Optional.empty();
    }

    return sessions
        .findByIdForUpdate(sessionId)
        .filter(session -> isOpenToDepartment(session, agencyId))
        .filter(session -> roomId.equals(session.getMatrixRoomId()))
        .map(
            session ->
                matrixMembership.tryJoinConsultantIntoRoom(consultant.get(), roomId, agencyToken));
  }

  static boolean isOpenToDepartment(Session session, Long agencyId) {
    return Objects.equals(agencyId, session.getAgencyId())
        && OPEN_STATUSES.contains(session.getStatus())
        && session.getConsultant() == null
        && !Boolean.TRUE.equals(session.getIsConsultantDirectlySet())
        && isNotBlank(session.getMatrixRoomId());
  }
}
