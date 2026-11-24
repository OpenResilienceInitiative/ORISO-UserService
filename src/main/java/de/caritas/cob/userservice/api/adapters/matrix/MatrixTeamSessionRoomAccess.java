package de.caritas.cob.userservice.api.adapters.matrix;

import de.caritas.cob.userservice.api.port.out.TeamSessionRoomAccess;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Matrix transport adapter for the Teamberatung read-only port. */
@Component
@RequiredArgsConstructor
public class MatrixTeamSessionRoomAccess implements TeamSessionRoomAccess {

  private final MatrixSynapseService matrixSynapseService;

  @Override
  public Optional<List<String>> getRoomMembers(String roomId) {
    return matrixSynapseService.getRoomMembers(roomId);
  }

  @Override
  public String loginAsUserAccessToken(String matrixUserId) {
    return matrixSynapseService.loginAsUserAccessToken(matrixUserId);
  }

  @Override
  public Optional<Map<String, Object>> getPowerLevels(String roomId, String accessToken) {
    return matrixSynapseService.getPowerLevels(roomId, accessToken);
  }

  @Override
  public Optional<Integer> makeUserReadOnly(
      String roomId,
      String userId,
      int readerPower,
      String ownerId,
      String ownerToken,
      String memberToken) {
    return matrixSynapseService.makeUserReadOnly(
        roomId, userId, readerPower, ownerId, ownerToken, memberToken);
  }
}
