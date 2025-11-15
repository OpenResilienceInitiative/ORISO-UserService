package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyPreAssignmentRoomService {

  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionService sessionService;

  public void ensureHoldingRoom(Session session, User user) {
    if (session == null || user == null) {
      return;
    }

    if (session.getMatrixRoomId() != null) {
      log.debug(
          "Session {} already has Matrix room {} configured, skipping agency holding room.",
          session.getId(),
          session.getMatrixRoomId());
      return;
    }

    if (session.getAgencyId() == null) {
      log.debug(
          "Session {} has no agency assigned, skipping Matrix holding room.", session.getId());
      return;
    }

    if (isBlank(user.getMatrixUserId()) || isBlank(user.getMatrixPassword())) {
      log.warn(
          "User {} missing Matrix credentials, skipping agency holding room for session {}",
          user.getUserId(),
          session.getId());
      return;
    }

    Optional<AgencyMatrixCredentialsDTO> credentialsOpt =
        matrixCredentialClient.fetchMatrixCredentials(session.getAgencyId());

    if (credentialsOpt.isEmpty()) {
      log.warn(
          "No Matrix service account available for agency {}. Session {} will not have holding room.",
          session.getAgencyId(),
          session.getId());
      return;
    }

    AgencyMatrixCredentialsDTO credentials = credentialsOpt.get();

    if (isBlank(credentials.getMatrixUserId()) || isBlank(credentials.getMatrixPassword())) {
      log.warn(
          "Matrix service account configuration incomplete for agency {}. Skipping holding room for session {}.",
          session.getAgencyId(),
          session.getId());
      return;
    }

    String agencyMatrixUsername = extractLocalPart(credentials.getMatrixUserId());
    String agencyToken =
        matrixSynapseService.loginUser(agencyMatrixUsername, credentials.getMatrixPassword());

    if (isBlank(agencyToken)) {
      log.error(
          "Failed to login Matrix service account {} for agency {}. Holding room skipped for session {}.",
          credentials.getMatrixUserId(),
          session.getAgencyId(),
          session.getId());
      return;
    }

    String roomAlias = buildRoomAlias(session.getId());
    String roomName = buildRoomName(session, credentials.getMatrixUserId());

    try {
      ResponseEntity<MatrixCreateRoomResponseDTO> response =
          matrixSynapseService.createRoom(roomName, roomAlias, agencyToken);

      if (response.getBody() == null || isBlank(response.getBody().getRoomId())) {
        log.error("Matrix create room returned empty body for session {}", session.getId());
        return;
      }

      String roomId = response.getBody().getRoomId();

      inviteUser(roomId, user, agencyToken);

      session.setMatrixRoomId(roomId);
      sessionService.saveSession(session);

      log.info(
          "Configured agency holding Matrix room {} for session {} (agency {}).",
          roomId,
          session.getId(),
          session.getAgencyId());

    } catch (MatrixCreateRoomException ex) {
      log.error(
          "Could not create agency holding room for session {}: {}",
          session.getId(),
          ex.getMessage());
    }
  }

  private void inviteUser(String roomId, User user, String agencyToken) {
    try {
      matrixSynapseService.inviteUserToRoom(roomId, user.getMatrixUserId(), agencyToken);

      String userMatrixUsername = extractLocalPart(user.getMatrixUserId());
      String userToken =
          matrixSynapseService.loginUser(userMatrixUsername, user.getMatrixPassword());
      if (!isBlank(userToken)) {
        matrixSynapseService.joinRoom(roomId, userToken);
      }
    } catch (MatrixInviteUserException ex) {
      log.error(
          "Failed to invite user {} to holding room {}: {}",
          user.getUserId(),
          roomId,
          ex.getMessage());
    }
  }

  private String extractLocalPart(String matrixUserId) {
    if (isBlank(matrixUserId)) {
      return matrixUserId;
    }
    if (matrixUserId.startsWith("@")) {
      matrixUserId = matrixUserId.substring(1);
    }
    int colonIndex = matrixUserId.indexOf(':');
    return colonIndex > 0 ? matrixUserId.substring(0, colonIndex) : matrixUserId;
  }

  private String buildRoomAlias(Long sessionId) {
    return "agency_hold_" + sessionId + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String buildRoomName(Session session, String matrixUserId) {
    return String.format("Agency %s pre-assignment #%d", matrixUserId, session.getId());
  }
}







