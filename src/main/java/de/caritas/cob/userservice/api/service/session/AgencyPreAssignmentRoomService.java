package de.caritas.cob.userservice.api.service.session;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateRoomException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixInviteUserException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRoomGateway;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgencyPreAssignmentRoomService {

  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull SessionRoomGateway sessionRoomGateway;
  private final @NonNull SessionService sessionService;
  private final @NonNull AgencySilentMembershipService agencySilentMembershipService;

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
        sessionRoomGateway.loginUser(agencyMatrixUsername, credentials.getMatrixPassword());

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
      String roomId = sessionRoomGateway.createRoom(roomName, roomAlias, agencyToken);
      if (isBlank(roomId)) {
        log.error("Matrix create room returned empty body for session {}", session.getId());
        return;
      }

      // Order is load-bearing (ADR-002 §1, #199): the department joins while the asker is not a
      // member yet. The asker's Matrix client discovers the room via /sync the moment they are
      // invited — independent of when this method returns — so inviting the asker first opens a
      // window in which their client can snapshot the membership list and encrypt the first
      // message for an audience that does not yet contain the department. Consultants-first makes
      // every membership view the asker's client can ever observe already include the department.
      joinAgencyConsultants(session, roomId, agencyToken);
      if (!inviteUser(roomId, user, agencyToken)) {
        log.error(
            "Asker {} could not be invited to Matrix holding room {} for session {}; room id will not be persisted.",
            user.getUserId(),
            roomId,
            session.getId());
        return;
      }

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
   * FE#811 / ADR-002 §1: the agency's counsellors become real room members here, while the room is
   * still empty, so an enquiry is readable to everyone entitled to pick it up and no one ever joins
   * after a Megolm session was already handed out.
   *
   * <p>A directly addressed enquiry (public counsellor link, appointment booking) is deliberately
   * excluded — the advice seeker chose one counsellor, and fanning that case out to the whole
   * department would widen its audience beyond what they consented to.
   *
   * <p>Department membership is a best-effort side effect, never a precondition (see {@link
   * AgencySilentMembershipService#joinAgencyConsultants}): since it now runs before the asker is
   * invited, an unexpected failure here must not abort the asker's own invite/join or the room
   * persist — otherwise a single consultant-lookup hiccup would kill the whole enquiry.
   */
  private void joinAgencyConsultants(Session session, String roomId, String agencyToken) {
    if (Boolean.TRUE.equals(session.getIsConsultantDirectlySet())) {
      log.debug(
          "Session {} is directly assigned to a consultant, skipping department membership.",
          session.getId());
      return;
    }

    try {
      agencySilentMembershipService.joinAgencyConsultants(
          session.getAgencyId(), roomId, agencyToken);
    } catch (RuntimeException ex) {
      log.error(
          "Department membership for room {} of session {} failed; continuing with asker-only room: {}",
          roomId,
          session.getId(),
          ex.getMessage());
    }
  }

  private boolean inviteUser(String roomId, User user, String agencyToken) {
    try {
      sessionRoomGateway.inviteUser(roomId, user.getMatrixUserId(), agencyToken);

      String userToken = sessionRoomGateway.loginAsUser(user.getMatrixUserId());
      if (!isBlank(userToken)) {
        return sessionRoomGateway.joinRoom(roomId, userToken);
      }
      return true;
    } catch (MatrixInviteUserException ex) {
      log.error(
          "Failed to invite user {} to holding room {}: {}",
          user.getUserId(),
          roomId,
          ex.getMessage());
      return false;
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
