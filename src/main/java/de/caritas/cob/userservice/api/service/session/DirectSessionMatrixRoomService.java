package de.caritas.cob.userservice.api.service.session;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Provisions a Matrix room for a session that was created via the direct-consultant registration
 * path (the {@code ?cid=<consultantId>} sharing link). Mirrors the room-creation logic that {@code
 * AssignEnquiryFacade} performs when a consultant accepts an enquiry, but runs at registration time
 * so both parties can start chatting immediately — no enquiry queue involved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectSessionMatrixRoomService {

  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull SessionService sessionService;
  private final @NonNull UserHelper userHelper;

  /**
   * Creates a Matrix room between {@code consultant} and the session's user, invites both parties,
   * and saves the room id on the session. No-ops if the session already has a Matrix room. Swallows
   * failures so that a Matrix outage does not break the registration flow — the caller still gets a
   * session object back.
   */
  public void provisionRoomForDirectSession(Session session, Consultant consultant) {
    if (session == null || consultant == null) {
      return;
    }
    if (session.getMatrixRoomId() != null && !session.getMatrixRoomId().isBlank()) {
      log.info(
          "Session {} already has Matrix room {}, skipping direct-session room provisioning",
          session.getId(),
          session.getMatrixRoomId());
      return;
    }

    try {
      ensureConsultantMatrixAccount(consultant);
      var user = session.getUser();
      if (user == null || user.getMatrixUserId() == null) {
        log.warn(
            "User for session {} has no Matrix account, cannot provision direct-session room",
            session.getId());
        return;
      }
      if (consultant.getMatrixUserId() == null) {
        log.warn(
            "Consultant {} has no Matrix account, cannot provision direct-session room",
            consultant.getUsername());
        return;
      }

      var roomName = "Session " + session.getId() + " - " + consultant.getUsername();
      var roomAlias = "session_" + session.getId();

      var createRoomResponse =
          matrixSynapseService.createRoomAsMatrixUser(
              roomName, roomAlias, consultant.getMatrixUserId());

      if (createRoomResponse == null
          || createRoomResponse.getBody() == null
          || createRoomResponse.getBody().getRoomId() == null) {
        log.error(
            "Matrix createRoomAsConsultant returned no room id for session {}", session.getId());
        return;
      }

      var roomId = createRoomResponse.getBody().getRoomId();
      session.setMatrixRoomId(roomId);
      sessionService.saveSession(session);

      var consultantToken =
          matrixSynapseService.loginAsUserAccessToken(consultant.getMatrixUserId());
      if (consultantToken == null) {
        log.error(
            "Could not create Matrix token for consultant {} after creating room {} for session {}",
            consultant.getUsername(),
            roomId,
            session.getId());
        return;
      }

      try {
        matrixSynapseService.inviteUserToRoom(roomId, user.getMatrixUserId(), consultantToken);
      } catch (Exception ex) {
        log.warn(
            "Failed to invite user {} to direct-session room {}: {}",
            user.getMatrixUserId(),
            roomId,
            ex.getMessage());
      }

      if (user.getMatrixUserId() != null) {
        var userToken = matrixSynapseService.loginAsUserAccessToken(user.getMatrixUserId());
        if (userToken != null) {
          boolean joined = matrixSynapseService.joinRoom(roomId, userToken);
          if (joined) {
            log.info("User {} auto-joined direct-session room {}", user.getUsername(), roomId);
          } else {
            log.warn("User {} failed to auto-join direct-session room {}", user.getUsername(), roomId);
          }
        } else {
          log.warn(
              "Could not create Matrix token for user {} to auto-join direct-session room {}",
              user.getUsername(),
              roomId);
        }
      }

      boolean consultantJoined = matrixSynapseService.joinRoom(roomId, consultantToken);
      if (consultantJoined) {
        log.info(
            "Consultant {} confirmed in direct-session room {} (session {})",
            consultant.getUsername(),
            roomId,
            session.getId());
      }

      log.info(
          "Provisioned direct-session Matrix room {} for session {} (consultant {}, user {})",
          roomId,
          session.getId(),
          consultant.getUsername(),
          user.getUsername());
    } catch (Exception ex) {
      log.error(
          "Failed to provision direct-session Matrix room for session {}: {}",
          session.getId(),
          ex.getMessage(),
          ex);
    }
  }

  private void ensureConsultantMatrixAccount(Consultant consultant) {
    if (consultant.getMatrixUserId() != null) {
      return;
    }
    String generatedMatrixPassword = userHelper.getRandomPassword();
    try {
      var response =
          matrixSynapseService.createUser(
              consultant.getUsername(),
              generatedMatrixPassword,
              consultant.getFirstName() + " " + consultant.getLastName());
      if (response != null
          && response.getBody() != null
          && response.getBody().getUserId() != null) {
        consultant.setMatrixUserId(response.getBody().getUserId());
        consultantRepository.save(consultant);
        log.info(
            "Created Matrix account for consultant {} during direct-session provisioning",
            consultant.getUsername());
        return;
      }
    } catch (Exception ex) {
      log.error(
          "Failed to create Matrix account for consultant {} during direct-session provisioning",
          consultant.getUsername(),
          ex);
    }
  }
}
