package de.caritas.cob.userservice.api.adapters.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionAssignmentChatGateway;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Matrix adapter for session-assignment room membership changes. */
@Component
@RequiredArgsConstructor
public class MatrixSessionAssignmentGateway implements SessionAssignmentChatGateway {

  private final MatrixSynapseService matrixSynapseService;

  @Override
  public void prepareAssignment(Session session, Consultant consultant) {
    String roomId = session.getMatrixRoomId();
    String newConsultantId = consultant.getMatrixUserId();
    String currentConsultantId =
        session.getConsultant() == null ? null : session.getConsultant().getMatrixUserId();

    if (!MatrixIds.isRoomId(roomId)
        || !MatrixIds.isUserId(newConsultantId)
        || !MatrixIds.isUserId(currentConsultantId)) {
      throw new InternalServerErrorException(
          String.format(
              "Cannot hand over session %s without a Matrix room and Matrix consultant identities",
              session.getId()));
    }

    String currentConsultantToken =
        matrixSynapseService.loginAsUserAccessToken(currentConsultantId);
    if (isBlank(currentConsultantToken)) {
      throw new InternalServerErrorException(
          String.format("Could not authorize Matrix handover for session %s", session.getId()));
    }

    try {
      matrixSynapseService.inviteUserToRoom(roomId, newConsultantId, currentConsultantToken);
    } catch (Exception exception) {
      throw new InternalServerErrorException(
          String.format(
              "Could not invite consultant %s to Matrix room %s", consultant.getId(), roomId),
          exception);
    }

    String newConsultantToken = matrixSynapseService.loginAsUserAccessToken(newConsultantId);
    boolean joined =
        !isBlank(newConsultantToken) && matrixSynapseService.joinRoom(roomId, newConsultantToken);
    boolean promoted =
        joined
            && matrixSynapseService.setUserPowerLevel(
                roomId, newConsultantId, 100, currentConsultantToken);
    if (!joined || !promoted) {
      throw new InternalServerErrorException(
          String.format(
              "Could not complete Matrix handover of session %s to consultant %s",
              session.getId(), consultant.getId()));
    }
  }

  @Override
  public List<String> findMemberIds(String roomId) {
    if (!MatrixIds.isRoomId(roomId)) {
      throw new InternalServerErrorException("Cannot read members without a Matrix room ID");
    }
    return matrixSynapseService
        .getRoomMembers(roomId)
        .orElseThrow(
            () ->
                new InternalServerErrorException(
                    String.format("Could not read members of Matrix room %s", roomId)));
  }

  @Override
  public void removeConsultants(
      Session session, Consultant actingConsultant, List<Consultant> consultants) {
    if (consultants.isEmpty()) {
      return;
    }
    String roomId = session.getMatrixRoomId();
    String moderatorToken =
        matrixSynapseService.loginAsUserAccessToken(actingConsultant.getMatrixUserId());
    if (!MatrixIds.isRoomId(roomId) || isBlank(moderatorToken)) {
      throw new InternalServerErrorException(
          String.format("Could not authorize Matrix room cleanup for session %s", session.getId()));
    }

    for (Consultant consultant : consultants) {
      String matrixUserId = consultant.getMatrixUserId();
      if (MatrixIds.isUserId(matrixUserId)
          && !matrixSynapseService.removeUserFromRoom(roomId, matrixUserId, moderatorToken)) {
        throw new InternalServerErrorException(
            String.format(
                "Could not remove consultant %s from Matrix room %s", consultant.getId(), roomId));
      }
    }
  }
}
