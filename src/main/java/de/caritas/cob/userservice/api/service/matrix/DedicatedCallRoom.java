package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FE#2209: the evidence that a Matrix room is the dedicated call room of one call, shared by both
 * paths that create a call binding: the plaintext invite ({@link
 * MatrixCallInviteNotificationService}) and the 1:1 registration ({@link
 * MatrixSessionCallBindingService#bind}). Security review 5901360577: the invite path used to skip
 * the registry and creator checks.
 */
@Component
@RequiredArgsConstructor
public class DedicatedCallRoom {
  private final OrisoRoomRegistry rooms;
  private final MatrixSynapseService matrix;

  /**
   * No Matrix call. Never the conversation itself, and never one of ORISO's own conversation rooms:
   * otherwise a member could bind one conversation's call to another conversation's room, the
   * observer would join it as admin, and admitted guests would be invited into it.
   */
  public boolean isConversationRoom(String sourceRoomId, String mediaRoomId) {
    return mediaRoomId.equals(sourceRoomId) || rooms.isKnownConversationRoom(mediaRoomId);
  }

  /**
   * Matrix evidence, read with the caller's authority and no transaction open (#1630): the room's
   * binding state names this call of this conversation, and the caller created the room and wrote
   * that binding (the Frontend creates the call room with the binding in its initial state).
   */
  public boolean isBoundBy(String sourceRoomId, String callId, String mediaRoomId, String caller) {
    var roomBinding = matrix.getCallRoomBinding(mediaRoomId, caller).orElse(null);
    if (roomBinding == null
        || !callId.equals(roomBinding.get("call_id"))
        || !sourceRoomId.equals(roomBinding.get("source_room_id"))) {
      return false;
    }
    var origin = matrix.getCallRoomOrigin(mediaRoomId, caller).orElse(null);
    return origin != null
        && caller.equals(origin.creatorMatrixId())
        && caller.equals(origin.bindingSenderMatrixId());
  }
}
