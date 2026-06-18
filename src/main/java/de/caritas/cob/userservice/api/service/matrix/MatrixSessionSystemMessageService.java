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
    resolveMatrixCredentials(session)
        .ifPresent(
            credentials ->
                sendUserLeftMessage(session.getId(), roomId, displayUsername, credentials));
  }

  private void sendUserLeftMessage(
      Long sessionId, String matrixRoomId, String displayUsername, MatrixCredentials credentials) {
    var accessToken = credentials.accessToken(matrixSynapseService);
    if (accessToken == null) {
      log.warn(
          "Skipping Matrix user-left message for session {} — token unavailable for {}",
          sessionId,
          credentials.principal());
      return;
    }

    var body = buildUserLeftChatBody(displayUsername);
    var response = matrixSynapseService.sendMessage(matrixRoomId, body, accessToken);
    if (response != null && response.containsKey("error")) {
      log.warn(
          "Matrix user-left message for session {} failed: {}", sessionId, response.get("error"));
    }
  }

  private Optional<MatrixCredentials> resolveMatrixCredentials(Session session) {
    User user = session.getUser();
    if (user != null && isNotBlank(user.getMatrixUserId())) {
      return Optional.of(MatrixCredentials.forMatrixUser(user.getMatrixUserId()));
    }

    Consultant consultant = session.getConsultant();
    if (consultant != null && isNotBlank(consultant.getId())) {
      consultant = consultantService.getConsultant(consultant.getId()).orElse(consultant);
      if (isNotBlank(consultant.getMatrixUserId())) {
        return Optional.of(MatrixCredentials.forMatrixUser(consultant.getMatrixUserId()));
      }
    }

    return agencyMatrixCredentialClient
        .fetchMatrixCredentials(session.getAgencyId())
        .filter(dto -> isNotBlank(dto.getMatrixUserId()) && isNotBlank(dto.getMatrixPassword()))
        .map(
            dto ->
                MatrixCredentials.forPasswordLogin(
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

  private static final class MatrixCredentials {
    private final String matrixUserId;
    private final String password;
    private final String username;

    private MatrixCredentials(String matrixUserId, String username, String password) {
      this.matrixUserId = matrixUserId;
      this.username = username;
      this.password = password;
    }

    private static MatrixCredentials forMatrixUser(String matrixUserId) {
      return new MatrixCredentials(matrixUserId, null, null);
    }

    private static MatrixCredentials forPasswordLogin(String username, String password) {
      return new MatrixCredentials(null, username, password);
    }

    private String accessToken(MatrixSynapseService matrixSynapseService) {
      if (isNotBlank(matrixUserId)) {
        return matrixSynapseService.loginAsUserAccessToken(matrixUserId);
      }
      return matrixSynapseService.loginUser(username, password);
    }

    private String principal() {
      return isNotBlank(matrixUserId) ? matrixUserId : username;
    }
  }
}
