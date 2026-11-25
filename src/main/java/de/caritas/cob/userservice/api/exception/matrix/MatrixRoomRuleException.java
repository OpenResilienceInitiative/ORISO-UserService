package de.caritas.cob.userservice.api.exception.matrix;

import lombok.Getter;

/**
 * Matrix created the room, but a rule every new room must carry could not be written into it. The
 * room must not be handed out; it carries its ID so the creator can remove it instead of leaving an
 * unreachable room (and its alias) on the homeserver.
 */
@Getter
public class MatrixRoomRuleException extends MatrixCreateRoomException {

  private final String roomId;

  public MatrixRoomRuleException(String message, String roomId) {
    super(message);
    this.roomId = roomId;
  }
}
