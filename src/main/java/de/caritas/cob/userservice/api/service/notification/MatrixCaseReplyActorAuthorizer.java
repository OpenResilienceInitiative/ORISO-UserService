package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Current write authority for a reply in the primary case room, not broad case read permission. */
@Service
@RequiredArgsConstructor
public class MatrixCaseReplyActorAuthorizer {
  private final @NonNull ConsultantRepository consultants;
  private final @NonNull MatrixSynapseService matrix;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;

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
    if (!isCurrentAssignedOrTeamWriter(session, actor)) {
      return false;
    }
    if (!requireCurrentRoomMembership) {
      // A Matrix event can only be emitted into a room in which the sender was a member at that
      // time. The worker must still recheck current membership before sending the queued mail.
      return true;
    }
    return hasCurrentRoomMembers(session, senderMatrixUserId, session.getUser().getMatrixUserId());
  }

  // Tenant/current-identity validation remains the caller's responsibility. This shared
  // decision also serves the controller's documented tenant-one legacy ownership boundary.
  // FE#2494 *4 (decision d): Teamberatung colleagues read but never write, so the assigned
  // counsellor is the only writer in a team session too.
  public boolean isCurrentAssignedOrTeamWriter(Session session, Consultant actor) {
    if (membersOnlyChatAccess.excludes(session, actor)) return false;
    return session.isAdvisedBy(actor);
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
}
