package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** Posts in-room system messages to Matrix sessions (e.g. user left the chat). */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixSessionSystemMessageService {

  public static final String SYSTEM_NOTIFICATION_PREFIX = "[SYSTEM_NOTIFICATION]";
  public static final String USER_LEFT_CHAT_TYPE = "USER_LEFT_CHAT";

  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull AgencyMatrixCredentialClient agencyMatrixCredentialClient;
  private final @NonNull SessionService sessionService;
  private final @NonNull ConsultantService consultantService;

  /**
   * Notifies participants in the Matrix room that the advice seeker left the conversation.
   *
   * @param session the session being finished or deleted
   */
  public void postUserLeftChatMessage(Session session) {
    if (session == null || session.getId() == null) {
      return;
    }

    var matrixRoomId = session.getMatrixRoomId();
    if (isBlank(matrixRoomId)) {
      matrixRoomId =
          sessionService.getSession(session.getId()).map(Session::getMatrixRoomId).orElse(null);
    }
    if (isBlank(matrixRoomId)) {
      return;
    }

    var displayUsername = resolveDisplayUsername(session);
    var roomId = matrixRoomId;
    var accessToken = resolveMatrixAccessToken(session);
    if (accessToken.isEmpty()) {
      log.warn(
          "Skipping Matrix user-left message for session {} — no Matrix account available",
          session.getId());
      return;
    }
    sendUserLeftMessage(session.getId(), roomId, displayUsername, accessToken.get());
  }

  private void sendUserLeftMessage(
      Long sessionId, String matrixRoomId, String displayUsername, String accessToken) {
    var body = buildUserLeftChatBody(displayUsername);
    var response = matrixSynapseService.sendMessage(matrixRoomId, body, accessToken);
    if (response != null && response.containsKey("error")) {
      log.warn(
          "Matrix user-left message for session {} failed: {}", sessionId, response.get("error"));
    }
  }

  /**
   * Resolves a Matrix access token for posting a system message to the session room.
   *
   * <p>Priority: consultant (stable room member) → advice seeker → agency service account. Each
   * candidate is tried in order; the first successful impersonation is returned so that a transient
   * failure for one account does not silently suppress the notification.
   */
  private Optional<String> resolveMatrixAccessToken(Session session) {
    // 1. Consultant — preferred sender: remains in the room after the session ends.
    Consultant consultant = session.getConsultant();
    if (consultant != null && isNotBlank(consultant.getId())) {
      consultant = consultantService.getConsultant(consultant.getId()).orElse(consultant);
      if (isNotBlank(consultant.getMatrixUserId())) {
        String token = matrixSynapseService.loginUserViaAdmin(consultant.getMatrixUserId());
        if (token != null) {
          return Optional.of(token);
        }
        log.warn(
            "Admin impersonation failed for consultant {} (session {}), trying next candidate",
            consultant.getMatrixUserId(),
            session.getId());
      }
    }

    // 2. Advice seeker — still in the room at notification time (deactivation happens after).
    User user = session.getUser();
    if (user != null && isNotBlank(user.getMatrixUserId())) {
      String token = matrixSynapseService.loginUserViaAdmin(user.getMatrixUserId());
      if (token != null) {
        return Optional.of(token);
      }
      log.warn(
          "Admin impersonation failed for user {} (session {}), trying agency fallback",
          user.getMatrixUserId(),
          session.getId());
    }

    // 3. Agency service account — password-based login for system accounts.
    return agencyMatrixCredentialClient
        .fetchMatrixCredentials(session.getAgencyId())
        .filter(dto -> isNotBlank(dto.getMatrixUserId()) && isNotBlank(dto.getMatrixPassword()))
        .map(
            dto ->
                matrixSynapseService.loginUser(
                    extractMatrixLocalpart(dto.getMatrixUserId()), dto.getMatrixPassword()));
  }

  private String extractMatrixLocalpart(String matrixUserId) {
    if (matrixUserId.startsWith("@")) {
      return matrixUserId.substring(1).split(":")[0];
    }
    return matrixUserId;
  }

  private String resolveDisplayUsername(Session session) {
    var username = extractUsername(session.getUser());
    if (isNotBlank(username)) {
      return username;
    }
    if (session.getId() == null) {
      return "";
    }
    return sessionService
        .getSession(session.getId())
        .map(Session::getUser)
        .map(this::extractUsername)
        .filter(StringUtils::isNotBlank)
        .orElse("");
  }

  private String extractUsername(User user) {
    if (user == null) {
      return "";
    }
    return user.getUsername();
  }

  private String buildUserLeftChatBody(String username) {
    var safeUsername = username == null ? "" : username.replace("\\", "\\\\").replace("\"", "\\\"");
    return SYSTEM_NOTIFICATION_PREFIX
        + "{\"type\":\""
        + USER_LEFT_CHAT_TYPE
        + "\",\"username\":\""
        + safeUsername
        + "\"}";
  }
}
