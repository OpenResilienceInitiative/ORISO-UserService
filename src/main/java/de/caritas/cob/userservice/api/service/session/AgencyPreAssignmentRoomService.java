package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;

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

    if (isBlank(user.getMatrixUserId())) {
      log.warn(
          "User {} missing Matrix user ID, skipping agency holding room for session {}",
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

      inviteEligibleDepartmentConsultants(roomId, session, agencyToken);
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

  /**
   * ADR-002: department consultants are silent Matrix members from room creation. Joining them
   * before the asker joins guarantees that the first encrypted event includes their devices in the
   * Megolm audience; accepting or revealing the case later must never be a late-membership event.
   */
  private void inviteEligibleDepartmentConsultants(
      String roomId, Session session, String agencyToken) {
    List<Consultant> agencyConsultants =
        consultantRepository.findByConsultantAgenciesAgencyIdAndDeleteDateIsNull(
            session.getAgencyId());
    Set<String> topicConsultantIds = resolveTopicConsultantIds(session);

    agencyConsultants.stream()
        .filter(consultant -> consultant != null && !isBlank(consultant.getMatrixUserId()))
        .filter(
            consultant ->
                topicConsultantIds.isEmpty() || topicConsultantIds.contains(consultant.getId()))
        .forEach(consultant -> inviteConsultant(roomId, consultant, agencyToken));
  }

  private Set<String> resolveTopicConsultantIds(Session session) {
    if (session.getMainTopicId() == null) {
      return Collections.emptySet();
    }
    return consultantTopicRepository.findConsultantIdsByTopicId(session.getMainTopicId()).stream()
        .collect(Collectors.toSet());
  }

  private void inviteConsultant(String roomId, Consultant consultant, String agencyToken) {
    try {
      matrixSynapseService.inviteUserToRoom(roomId, consultant.getMatrixUserId(), agencyToken);
      String consultantToken =
          matrixSynapseService.loginAsUserAccessToken(consultant.getMatrixUserId());
      if (isBlank(consultantToken) || !matrixSynapseService.joinRoom(roomId, consultantToken)) {
        log.warn(
            "Eligible consultant {} could not join holding room {} before first message",
            consultant.getUsername(),
            roomId);
      }
    } catch (MatrixInviteUserException ex) {
      log.error(
          "Failed to add eligible consultant {} to holding room {}: {}",
          consultant.getUsername(),
          roomId,
          ex.getMessage());
    }
  }

  private void inviteUser(String roomId, User user, String agencyToken) {
    try {
      matrixSynapseService.inviteUserToRoom(roomId, user.getMatrixUserId(), agencyToken);

      String userToken = matrixSynapseService.loginAsUserAccessToken(user.getMatrixUserId());
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
    return MatrixIds.localpartLenient(matrixUserId);
  }

  private String buildRoomAlias(Long sessionId) {
    return "agency_hold_" + sessionId + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String buildRoomName(Session session, String matrixUserId) {
    return String.format("Agency %s pre-assignment #%d", matrixUserId, session.getId());
  }
}
