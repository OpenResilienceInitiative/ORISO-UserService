package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.MatrixIds;
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
  public static final String CASE_HANDOVER_GRANTED_TYPE = "CASE_HANDOVER_GRANTED";

  private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

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

  /**
   * Notifies the room that a new counsellor took over the case (case handover GRANTED). The message
   * is a normal m.text event with the [SYSTEM_NOTIFICATION] JSON envelope the web client renders as
   * a system card. Posted as the new (requester) consultant when possible.
   *
   * @param session the handed-over session (room + participants)
   * @param newAdvisorName display name of the consultant who took over
   * @param reasonLabel the selected handover reason label
   * @param explanation the free-text explanation given by the requester
   * @param description localized client-facing template text (nullable)
   */
  public void postCaseHandoverGrantedMessage(
      Session session,
      String newAdvisorName,
      String reasonLabel,
      String explanation,
      String description) {
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

    var body = buildCaseHandoverGrantedBody(newAdvisorName, reasonLabel, explanation, description);
    if (body == null) {
      return;
    }
    var roomId = matrixRoomId;
    resolveMatrixCredentialsPreferConsultant(session)
        .ifPresent(credentials -> sendSystemMessage(session.getId(), roomId, body, credentials));
  }

  private void sendSystemMessage(
      Long sessionId, String matrixRoomId, String body, MatrixCredentials credentials) {
    var accessToken = credentials.accessToken(matrixSynapseService);
    if (accessToken == null) {
      log.warn(
          "Skipping Matrix system message for session {} — token unavailable for {}",
          sessionId,
          credentials.principal());
      return;
    }
    var response = matrixSynapseService.sendMessage(matrixRoomId, body, accessToken);
    if (response != null && response.containsKey("error")) {
      log.warn("Matrix system message for session {} failed: {}", sessionId, response.get("error"));
    }
  }

  private Optional<MatrixCredentials> resolveMatrixCredentialsPreferConsultant(Session session) {
    Consultant consultant = session.getConsultant();
    if (consultant != null && isNotBlank(consultant.getId())) {
      consultant = consultantService.getConsultant(consultant.getId()).orElse(consultant);
      if (isNotBlank(consultant.getMatrixUserId())) {
        return Optional.of(MatrixCredentials.forMatrixUser(consultant.getMatrixUserId()));
      }
    }
    return resolveMatrixCredentials(session);
  }

  private String buildCaseHandoverGrantedBody(
      String newAdvisorName, String reasonLabel, String explanation, String description) {
    var payload = new java.util.LinkedHashMap<String, String>();
    payload.put("type", CASE_HANDOVER_GRANTED_TYPE);
    if (isNotBlank(newAdvisorName)) {
      payload.put("username", newAdvisorName);
    }
    if (isNotBlank(description)) {
      payload.put("description", description);
    }
    if (isNotBlank(reasonLabel)) {
      payload.put("reasonLabel", reasonLabel);
    }
    if (isNotBlank(explanation)) {
      payload.put("explanation", explanation);
    }
    try {
      return SYSTEM_NOTIFICATION_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      log.warn("Could not serialize case-handover system message payload", exception);
      return null;
    }
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
      return MatrixIds.localpart(matrixUserId);
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
