package de.caritas.cob.userservice.api.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound room boundary for Teamberatung read-only access (ORISO-Frontend#2494 *4, decision d).
 * The session module reads who is in a case room and lowers colleagues to readers through this
 * port; the Matrix transport stays inside the adapter.
 */
public interface TeamSessionRoomAccess {

  /** Joined members of the room, or empty when the chat server could not be read. */
  Optional<List<String>> getRoomMembers(String roomId);

  /** A short-lived server operation token for the user, or null. */
  String loginAsUserAccessToken(String matrixUserId);

  /** The room's power-level state, or empty when it could not be read. */
  Optional<Map<String, Object>> getPowerLevels(String roomId, String accessToken);

  /**
   * Lowers {@code userId} to {@code readerPower}. {@code memberToken} is used only when the member
   * holds the owner's level and must lower itself.
   *
   * @return the resulting power level, or empty when it could not be set
   */
  Optional<Integer> makeUserReadOnly(
      String roomId,
      String userId,
      int readerPower,
      String ownerId,
      String ownerToken,
      String memberToken);
}
