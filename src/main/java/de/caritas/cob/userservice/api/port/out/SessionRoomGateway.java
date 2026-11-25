package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixJoinRateLimitedException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixJoinTemporarilyUnavailableException;

/**
 * Outbound room boundary owned by the session/consultant module.
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

  /**
   * One invite attempt that never waits on Synapse's rate limiter: a 429 is thrown as {@link
   * de.caritas.cob.userservice.api.exception.matrix.MatrixInviteRateLimitedException} carrying the
   * server's retry delay.
   */
  void inviteUserOnce(String roomId, String userId, String accessToken)
      throws MatrixInviteUserException;

  /** True only when Matrix confirms the user is joined; an unknown state answers false. */
  boolean isJoinedMember(String roomId, String userId);

  boolean joinRoom(String roomId, String accessToken);

  /**
   * One join attempt without waiting or retrying. False means a terminal refusal. Transient
   * failures and rate limits let the caller preserve the prepared room and retry the same join.
   */
  boolean joinRoomOnce(String roomId, String accessToken)
      throws MatrixJoinRateLimitedException, MatrixJoinTemporarilyUnavailableException;

  boolean ensureAdminInRoom(String roomId, String memberMatrixUserId);

  boolean setUserPowerLevel(String roomId, String matrixUserId, int powerLevel, String accessToken);

  boolean removeUserFromRoom(String roomId, String matrixUserId, String accessToken);

  /** Requests that the token's owner leave the room. */
  void leaveRoom(String roomId, String accessToken);

  /** True only when room state confirms there is no active membership or invitation. */
  boolean isConfirmedOutOfRoom(String roomId, String matrixUserId);

  String userIdFor(String localpart);
}
