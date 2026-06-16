package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  /**
   * Notifies participants in the Matrix room that the advice seeker left the conversation.
   *
   * @param session the session being finished
   */
  public void postUserLeftChatMessage(Session session) {
    if (session == null || isBlank(session.getMatrixRoomId())) {
      return;
    }

    resolveMatrixCredentials(session)
        .ifPresent(credentials -> sendUserLeftMessage(session, credentials));
  }

  private void sendUserLeftMessage(Session session, MatrixCredentials credentials) {
    var accessToken =
        matrixSynapseService.loginUser(credentials.username(), credentials.password());
    if (accessToken == null) {
      log.warn(
          "Skipping Matrix user-left message for session {} — login failed for {}",
          session.getId(),
          credentials.username());
      return;
    }

    var username = resolveDisplayUsername(session);
    var body = buildUserLeftChatBody(username);
    var response = matrixSynapseService.sendMessage(session.getMatrixRoomId(), body, accessToken);
    if (response != null && response.containsKey("error")) {
      log.warn(
          "Matrix user-left message for session {} failed: {}",
          session.getId(),
          response.get("error"));
    }
  }

  private java.util.Optional<MatrixCredentials> resolveMatrixCredentials(Session session) {
    Consultant consultant = session.getConsultant();
    if (consultant != null
        && isNotBlank(consultant.getMatrixUserId())
        && isNotBlank(consultant.getMatrixPassword())) {
      return java.util.Optional.of(
          new MatrixCredentials(
              extractMatrixLocalpart(consultant.getMatrixUserId()),
              consultant.getMatrixPassword()));
    }

    return agencyMatrixCredentialClient
        .fetchMatrixCredentials(session.getAgencyId())
        .filter(dto -> isNotBlank(dto.getMatrixUserId()) && isNotBlank(dto.getMatrixPassword()))
        .map(
            dto ->
                new MatrixCredentials(
                    extractMatrixLocalpart(dto.getMatrixUserId()), dto.getMatrixPassword()));
  }

  private String extractMatrixLocalpart(String matrixUserId) {
    if (matrixUserId.startsWith("@")) {
      return matrixUserId.substring(1).split(":")[0];
    }
    return matrixUserId;
  }

  private String resolveDisplayUsername(Session session) {
    User user = session.getUser();
    if (user == null) {
      return "";
    }
    if (isNotBlank(user.getUsername())) {
      return user.getUsername();
    }
    return "";
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
    private final String username;
    private final String password;

    private MatrixCredentials(String username, String password) {
      this.username = username;
      this.password = password;
    }

    private String username() {
      return username;
    }

    private String password() {
      return password;
    }
  }
}
