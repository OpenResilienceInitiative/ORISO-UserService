package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Puts people who open a direct invitation with an appointment into the conversation room and takes
 * guests out again (ORISO-Frontend#2252). The only place that adds an invited person to a Matrix
 * conversation room; everything else in the backend adds guests to a call's media room only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectInvitationRoomMembership {

  private final @NonNull MatrixSynapseService matrixSynapseService;

  /**
   * A later person in a shared appointment conversation: the room was created for the appointment,
   * so everybody in it came through the same invitation.
   */
  public void addToAppointmentRoom(Session session, User user) {
    join(session, user);
  }

  /**
   * An invited person joins an existing 1:1 ("Weitere Personen per Link einladen") as a plain
   * member, the way every other later member enters a 1:1: invited by the conversation's
   * counsellor, then joined with their own token (decision 4 of the Direkteinladung record,
   * revision 2026-11-14). No room setting, marker or power level changes: Megolm keeps earlier
   * messages unreadable, and the frontend history-key transfer asks a person in the room before it
   * hands over earlier keys.
   */
  public void addInvitedGuestToExistingRoom(Session session, User user) {
    var counsellor = counsellorMatrixId(session);
    if (!hasRoom(session) || counsellor == null || !MatrixIds.isUserId(user.getMatrixUserId())) {
      throw roomUnavailable();
    }
    var counsellorToken = matrixSynapseService.loginAsUserAccessToken(counsellor);
    if (counsellorToken == null) {
      throw roomUnavailable();
    }
    try {
      matrixSynapseService.inviteUserToRoom(
          session.getMatrixRoomId(), user.getMatrixUserId(), counsellorToken);
    } catch (Exception inviteError) {
      // An already invited or joined person answers with an error here; the join decides.
      log.debug(
          "Invite into the room of session {} did not succeed: {}",
          session.getId(),
          inviteError.getMessage());
    }
    var userToken = matrixSynapseService.loginAsUserAccessToken(user.getMatrixUserId());
    if (userToken == null || !matrixSynapseService.joinRoom(session.getMatrixRoomId(), userToken)) {
      throw roomUnavailable();
    }
  }

  /**
   * Takes a person out of the conversation room, as the conversation's counsellor. Never throws.
   *
   * @return whether the person is out: Matrix confirmed the removal, or there is no room or no
   *     Matrix person to remove
   */
  public boolean remove(Session session, String matrixUserId) {
    try {
      if (!hasRoom(session) || !MatrixIds.isUserId(matrixUserId)) {
        // No room, or no Matrix person: nobody can be in it.
        return true;
      }
      var counsellor = counsellorMatrixId(session);
      if (counsellor == null) {
        return false;
      }
      var token = matrixSynapseService.loginAsUserAccessToken(counsellor);
      return token != null
          && matrixSynapseService.kickUserFromRoom(session.getMatrixRoomId(), matrixUserId, token);
    } catch (RuntimeException failure) {
      log.warn(
          "Invited member could not be removed from the room of session {}: {}",
          session.getId(),
          failure.getClass().getSimpleName());
      return false;
    }
  }

  private void join(Session session, User user) {
    if (!hasRoom(session) || !MatrixIds.isUserId(user.getMatrixUserId())) {
      throw roomUnavailable();
    }
    var counsellor = counsellorMatrixId(session);
    var joined =
        matrixSynapseService.forceJoinUserToRoom(
            session.getMatrixRoomId(),
            user.getMatrixUserId(),
            counsellor == null ? List.of() : List.of(counsellor));
    if (!joined) {
      throw roomUnavailable();
    }
  }

  private static ResponseStatusException roomUnavailable() {
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
  }

  private static boolean hasRoom(Session session) {
    return MatrixIds.isRoomId(session.getMatrixRoomId());
  }

  private static String counsellorMatrixId(Session session) {
    var consultant = session.getConsultant();
    return consultant == null || !MatrixIds.isUserId(consultant.getMatrixUserId())
        ? null
        : consultant.getMatrixUserId();
  }
}
