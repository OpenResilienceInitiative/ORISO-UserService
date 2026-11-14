package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationRefusedException.Reason;
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

  static final String HIDDEN_HISTORY = "joined";

  private final @NonNull MatrixSynapseService matrixSynapseService;

  /**
   * A later person in a shared appointment conversation: the room was created for the appointment,
   * so everybody in it came through the same invitation.
   */
  public void addToAppointmentRoom(Session session, User user) {
    join(session, user);
  }

  /**
   * An invited person joins an existing 1:1 ("Weitere Personen per Link einladen"). They must see
   * only messages written after they joined (decision 4 of the Direkteinladung record).
   *
   * <p>TODO(CP3, ORISO-Frontend#2252): set {@code m.room.history_visibility} to "joined" on the 1:1
   * before the join, keep invited guests out of the frontend room-key transfer and key backup, and
   * add the privacy test that a guest cannot read earlier messages. Until then this fails closed: a
   * room that shows earlier messages to new members takes nobody (409
   * DIRECT_INVITATION_HISTORY_SHARED), as {@code InternalChatInvitationFacade} does.
   */
  public void addInvitedGuestToExistingRoom(Session session, User user) {
    var visibility = matrixSynapseService.getRoomHistoryVisibility(session.getMatrixRoomId());
    if (visibility.isEmpty()) {
      // Unknown is not "hidden": the person may try again once Matrix answers.
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "room_history_unknown");
    }
    if (!HIDDEN_HISTORY.equals(visibility.get())) {
      throw new DirectInvitationRefusedException(Reason.DIRECT_INVITATION_HISTORY_SHARED);
    }
    join(session, user);
  }

  /**
   * Takes a person out of the conversation room, as the conversation's counsellor. Never throws.
   *
   * @return whether Matrix confirmed the removal
   */
  public boolean remove(Session session, String matrixUserId) {
    try {
      var counsellor = counsellorMatrixId(session);
      if (counsellor == null || !MatrixIds.isUserId(matrixUserId) || !hasRoom(session)) {
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
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
    var counsellor = counsellorMatrixId(session);
    var joined =
        matrixSynapseService.forceJoinUserToRoom(
            session.getMatrixRoomId(),
            user.getMatrixUserId(),
            counsellor == null ? List.of() : List.of(counsellor));
    if (!joined) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
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
