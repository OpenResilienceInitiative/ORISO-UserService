package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Matrix transport adapter for the session module's room-provisioning port. */
@Component
@RequiredArgsConstructor
public class MatrixSessionRoomGateway implements SessionRoomGateway {

  private final MatrixSynapseService matrixSynapseService;
  private final MatrixConfig matrixConfig;

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
    var response =
        matrixSynapseService.createUser(encodeLocalpart(username), password, displayName);
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

  @Override
  public boolean setUserPowerLevel(
      String roomId, String matrixUserId, int powerLevel, String accessToken) {
    return matrixSynapseService.setUserPowerLevel(roomId, matrixUserId, powerLevel, accessToken);
  }

  @Override
  public boolean removeUserFromRoom(String roomId, String matrixUserId, String accessToken) {
    return matrixSynapseService.removeUserFromRoom(roomId, matrixUserId, accessToken);
  }

  @Override
  public String userIdFor(String localpart) {
    return "@" + encodeLocalpart(localpart) + ":" + matrixConfig.getServerName();
  }

  /** Maps application usernames using the Matrix UTF-8 escaping convention. */
  private static String encodeLocalpart(String username) {
    var result = new StringBuilder();
    for (byte value : username.getBytes(StandardCharsets.UTF_8)) {
      int character = Byte.toUnsignedInt(value);
      if (character >= 'A' && character <= 'Z') {
        character += 'a' - 'A';
      }
      if ((character >= 'a' && character <= 'z')
          || (character >= '0' && character <= '9')
          || "_-./+".indexOf(character) >= 0) {
        result.append((char) character);
      } else {
        result.append('=');
        result.append(Character.forDigit(character >>> 4, 16));
        result.append(Character.forDigit(character & 15, 16));
      }
    }
    return result.toString();
  }
}
