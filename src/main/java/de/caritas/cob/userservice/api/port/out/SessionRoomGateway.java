package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;

/**
 * Outbound room-provisioning boundary owned by the session/consultant module.
 *
 * <p>The application layer exchanges only stable identifiers and booleans. Matrix transport DTOs
 * and HTTP response wrappers remain inside the adapter.
 */
public interface SessionRoomGateway {

  String loginUser(String username, String password);

  String loginAsUser(String matrixUserId);

  String createRoom(String roomName, String roomAlias, String accessToken)
      throws MatrixCreateRoomException;

  String createRoomAsUser(String roomName, String roomAlias, String matrixUserId)
      throws MatrixCreateRoomException;

  String createUser(String username, String password, String displayName)
      throws MatrixCreateUserException;

  void inviteUser(String roomId, String userId, String accessToken)
      throws MatrixInviteUserException;

  boolean joinRoom(String roomId, String accessToken);

  boolean ensureAdminInRoom(String roomId, String memberMatrixUserId);
}
