package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Matrix transport adapter for the session module's room-provisioning port. */
@Component
@RequiredArgsConstructor
public class MatrixSessionRoomGateway implements SessionRoomGateway {

  private final MatrixSynapseService matrixSynapseService;

  @Override
  public String loginUser(String username, String password) {
    return matrixSynapseService.loginUser(username, password);
  }

  @Override
  public String loginAsUser(String matrixUserId) {
    return matrixSynapseService.loginAsUserAccessToken(matrixUserId);
  }

  @Override
  public String createRoom(String roomName, String roomAlias, String accessToken)
      throws MatrixCreateRoomException {
    var response = matrixSynapseService.createRoom(roomName, roomAlias, accessToken);
    return response == null || response.getBody() == null ? null : response.getBody().getRoomId();
  }

  @Override
  public String createRoomAsUser(String roomName, String roomAlias, String matrixUserId)
      throws MatrixCreateRoomException {
    var response = matrixSynapseService.createRoomAsMatrixUser(roomName, roomAlias, matrixUserId);
    return response == null || response.getBody() == null ? null : response.getBody().getRoomId();
  }

  @Override
  public String createUser(String username, String password, String displayName)
      throws MatrixCreateUserException {
    var response = matrixSynapseService.createUser(username, password, displayName);
    return response == null || response.getBody() == null ? null : response.getBody().getUserId();
  }

  @Override
  public void inviteUser(String roomId, String userId, String accessToken)
      throws MatrixInviteUserException {
    matrixSynapseService.inviteUserToRoom(roomId, userId, accessToken);
  }

  @Override
  public boolean joinRoom(String roomId, String accessToken) {
    return matrixSynapseService.joinRoom(roomId, accessToken);
  }

  @Override
  public boolean ensureAdminInRoom(String roomId, String memberMatrixUserId) {
    return matrixSynapseService.ensureAdminInRoom(roomId, memberMatrixUserId);
  }
}
