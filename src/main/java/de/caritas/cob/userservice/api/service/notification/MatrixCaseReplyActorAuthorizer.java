package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverReasonCodes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Current write authority for a reply in the primary case room, not broad case read permission. */
@Service
@RequiredArgsConstructor
public class MatrixCaseReplyActorAuthorizer {
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull ConsultantAgencyRepository agencies;
  private final @NonNull CaseHandoverRequestRepository handovers;
  private final @NonNull SessionSupervisorRepository supervisors;
  private final @NonNull MatrixSynapseService matrix;

  public boolean isCurrentWriter(
      Session session, String senderMatrixUserId, boolean requireCurrentRoomMembership) {
    if (session == null
        || session.getId() == null
        || session.getTenantId() == null
        || session.getTenantId() <= 0
        || session.getUser() == null
        || isBlank(session.getMatrixRoomId())
        || isBlank(senderMatrixUserId)) {
      return false;
    }
    Consultant actor =
        consultants.findByMatrixUserIdAndDeleteDateIsNull(senderMatrixUserId).orElse(null);
    if (actor == null
        || !Objects.equals(actor.getTenantId(), session.getTenantId())
        || Objects.equals(actor.getId(), session.getUser().getUserId())) {
      return false;
    }
    boolean assigned = session.isAdvisedBy(actor);
    if (!assigned && !isPermittedTeamWriter(session, actor)) {
      return false;
    }
    if (!requireCurrentRoomMembership) {
      // A Matrix event can only be emitted into a room in which the sender was a member at that
      // time. The worker must still recheck current membership before sending the queued mail.
      return true;
    }
    return hasCurrentRoomMembers(session, senderMatrixUserId, session.getUser().getMatrixUserId());
  }

  /** Unknown membership is retriable; a known mismatch is denied before SMTP. */
  public boolean hasCurrentRoomMembers(
      Session session, String sourceMatrixUserId, String recipientMatrixUserId) {
    if (session == null || isBlank(session.getMatrixRoomId())) {
      return false;
    }
    var members =
        matrix
            .getRoomMembers(session.getMatrixRoomId())
            .orElseThrow(() -> new IllegalStateException("Matrix room membership is unavailable"));
    return !isBlank(sourceMatrixUserId)
        && !isBlank(recipientMatrixUserId)
        && members.contains(sourceMatrixUserId)
        && members.contains(recipientMatrixUserId);
  }

  private boolean isPermittedTeamWriter(Session session, Consultant actor) {
    if (!session.isTeamSession()
        || session.getAgencyId() == null
        || !actor.isTeamConsultant()
        || !agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            actor.getId(), session.getAgencyId())
        || supervisors
            .findBySessionIdAndSupervisorConsultantIdAndIsActiveTrue(session.getId(), actor.getId())
            .isPresent()) {
      return false;
    }
    var grants =
        handovers.findActiveGrantExcluding(
            session.getId(),
            actor.getId(),
            null,
            List.of(Status.GRANTED, Status.GRANTED_PENDING_CLIENT_OPTOUT),
            LocalDateTime.now(),
            Pageable.unpaged());
    return grants.stream().noneMatch(MatrixCaseReplyActorAuthorizer::isReadOnlyCoAccess);
  }

  private static boolean isReadOnlyCoAccess(CaseHandoverRequest request) {
    if (request.getAccessType() != null) {
      return request.getAccessType() == AccessType.CO_ACCESS;
    }
    // Legacy rows have a null access_type; this is the same canonical reason mapping used by
    // CaseHandoverService.effectiveAccessType.
    return CaseHandoverReasonCodes.ADVICE_REQUESTED.equals(
        CaseHandoverReasonCodes.canonical(request.getReasonCode()));
  }
}
