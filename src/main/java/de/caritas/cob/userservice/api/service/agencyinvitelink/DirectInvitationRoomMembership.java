package de.caritas.cob.userservice.api.service.agencyinvitelink;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  static final String HISTORY_VISIBILITY_EVENT = "m.room.history_visibility";
  private static final int OWNER_LEVEL = 100;
  private static final int MODERATOR_LEVEL = 50;

  /**
   * Room state event that marks a member who came in by a direct invitation into an existing 1:1;
   * state key = the member's Matrix ID, content {@code {"link_id": <invitation id>, "since": <epoch
   * ms>}}, sent by the technical Matrix admin. Clients read it from the room state they already
   * hold and treat any such event as a mark, whatever its content; it is never lifted.
   */
  public static final String LINK_INVITED_MEMBER_EVENT = "org.oriso.direct_invitation_member";

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
   * only messages written after they joined (decision 4 of the Direkteinladung record), so before
   * the join, acting as the technical Matrix admin (the backend's own Matrix identity):
   *
   * <ol>
   *   <li>the admin joins the room with the owner power level;
   *   <li>the room requires power level 100 for {@code m.room.history_visibility} and {@link
   *       #LINK_INVITED_MEMBER_EVENT}, and at least 50 for redactions, so no ordinary member can
   *       show earlier messages again or forge, change or redact a mark;
   *   <li>{@code m.room.history_visibility} becomes "joined" and stays so: the server then never
   *       serves earlier events to the newcomer (re-read, never assumed);
   *   <li>the person is marked with {@link #LINK_INVITED_MEMBER_EVENT} (state key = their Matrix
   *       ID), so every client can tell, without asking the backend, that this member must never
   *       receive room keys for earlier messages (frontend {@code matrixRoomHistoryKeyTransfer}).
   * </ol>
   *
   * <p>Fails closed: if any step cannot be confirmed, nobody joins.
   */
  public void addInvitedGuestToExistingRoom(Session session, User user, Long linkId) {
    var counsellor = counsellorMatrixId(session);
    if (!hasRoom(session) || counsellor == null || !MatrixIds.isUserId(user.getMatrixUserId())) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
    var roomId = session.getMatrixRoomId();
    if (!matrixSynapseService.prepareAdminAsRoomOwner(roomId, counsellor)
        || !matrixSynapseService.lockRoomStateEvents(
            roomId,
            Map.of(HISTORY_VISIBILITY_EVENT, OWNER_LEVEL, LINK_INVITED_MEMBER_EVENT, OWNER_LEVEL),
            MODERATOR_LEVEL)) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
    hideEarlierMessages(roomId);
    var marker = new HashMap<String, Object>();
    marker.put("link_id", linkId);
    marker.put("since", System.currentTimeMillis());
    if (!matrixSynapseService.putRoomStateAsAdmin(
        roomId, LINK_INVITED_MEMBER_EVENT, user.getMatrixUserId(), marker)) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "conversation_room_unavailable");
    }
    join(session, user);
  }

  private void hideEarlierMessages(String roomId) {
    var visibility = matrixSynapseService.getRoomHistoryVisibility(roomId);
    if (visibility.isEmpty()) {
      // Unknown is not "hidden": the person may try again once Matrix answers.
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "room_history_unknown");
    }
    if (HIDDEN_HISTORY.equals(visibility.get())) {
      return;
    }
    var written =
        matrixSynapseService.putRoomStateAsAdmin(
            roomId, HISTORY_VISIBILITY_EVENT, "", Map.of("history_visibility", HIDDEN_HISTORY));
    if (!written
        || !Optional.of(HIDDEN_HISTORY)
            .equals(matrixSynapseService.getRoomHistoryVisibility(roomId))) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "room_history_not_hidden");
    }
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
