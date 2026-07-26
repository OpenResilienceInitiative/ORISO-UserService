package de.caritas.cob.userservice.api.admin.service.agency;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService.ResolvedRoomMember;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Removes a consultant who was a team consultant from the chat rooms of the given sessions.
 *
 * <p>Matrix-native: when an agency is detached from a consultant, that consultant must lose access
 * to the team sessions of that agency. Membership and removal both go through Matrix (the only chat
 * backend). Room membership is resolved from Matrix before detached consultants lose access.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoveConsultantFromSessionRoomsService {

  private final @NonNull GroupChatMembershipService groupChatMembershipService;

  /**
   * Removes the consultants who are not the directly assigned consultant (and not the asker) from
   * the Matrix rooms of the given sessions.
   *
   * @param sessions the sessions whose rooms the surplus consultants should be removed from
   */
  public void removeConsultantFromSessions(List<Session> sessions) {
    sessions.forEach(this::removeConsultantsFromSessionRoom);
  }

  private void removeConsultantsFromSessionRoom(Session session) {
    var matrixRoomId = groupChatMembershipService.resolveMatrixRoomId(session);
    if (StringUtils.isBlank(matrixRoomId)) {
      log.warn("Session {} has no Matrix room; cannot remove surplus consultants", session.getId());
      return;
    }

    observeConsultantsToRemove(session, matrixRoomId)
        .forEach(
            consultant ->
                groupChatMembershipService.removeMemberFromRoom(
                    matrixRoomId, consultant.matrixUserId()));
  }

  /**
   * The consultants currently in the session's Matrix room that must lose access: every human
   * consultant member except the session's directly assigned consultant. The asker is never a
   * consultant, so consultant membership already excludes them.
   */
  private List<ResolvedRoomMember> observeConsultantsToRemove(
      Session session, String matrixRoomId) {
    return groupChatMembershipService.resolveHumanMembers(matrixRoomId).stream()
        .filter(ResolvedRoomMember::consultant)
        .filter(member -> notDirectlyAssignedConsultant(session, member))
        .toList();
  }

  private boolean notDirectlyAssignedConsultant(Session session, ResolvedRoomMember member) {
    Consultant assigned = session.getConsultant();
    if (assigned == null) {
      return true;
    }
    return !member.accountId().equals(assigned.getId());
  }
}
