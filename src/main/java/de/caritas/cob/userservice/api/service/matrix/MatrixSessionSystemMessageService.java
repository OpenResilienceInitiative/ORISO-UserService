package de.caritas.cob.userservice.api.service.matrix;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
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
  public static final String FIRST_RESPONSE_TYPE = "FIRST_RESPONSE";
  public static final String FURTHER_COUNSELLING_RECOMMENDED_TYPE =
      "FURTHER_COUNSELLING_RECOMMENDED";
  public static final String TOPIC_DUPLICATE_STARTED_TYPE = "TOPIC_DUPLICATE_STARTED";
  public static final String TOPIC_DUPLICATE_ENDED_TYPE = "TOPIC_DUPLICATE_ENDED";

  private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** Who ended the chat; sent as {@code endedBy} so clients can show the right text. */
  public enum EndedBy {
    CONSULTANT,
    ADVICE_SEEKER
  }

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
    postUserLeftChatMessage(session, null);
  }

  /**
   * Same as {@link #postUserLeftChatMessage(Session)}, naming who ended the chat.
   *
   * @param session the session being finished or deleted
   * @param endedBy the person who ended it; {@code null} for system ends (omits {@code endedBy})
   */
  public void postUserLeftChatMessage(Session session, EndedBy endedBy) {
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
                sendUserLeftMessage(
                    session.getId(), roomId, displayUsername, endedBy, credentials));
  }

  /**
   * Event-time metadata only; never reconstruct historical consent from current tenant settings.
   */
  public record GrantedAccessMetadata(
      Long requestId, CaseHandoverConsentMode clientConsent, AccessType accessType) {}

  /**
   * Notifies the room that a new counsellor took over the case (case handover GRANTED). The message
   * is a normal m.text event with the [SYSTEM_NOTIFICATION] JSON envelope the web client renders as
   * a system card. Posted as the new (requester) consultant when possible.
   *
   * @param session the handed-over session (room + participants)
   * @param newAdvisorName display name of the consultant who took over
   * @param description localized client-facing template text (nullable)
   */
  public void postCaseHandoverGrantedMessage(
      Session session, String newAdvisorName, String description) {
    postCaseHandoverGrantedMessage(session, newAdvisorName, description, null);
  }

  public void postCaseHandoverGrantedMessage(
      Session session, String newAdvisorName, String description, GrantedAccessMetadata metadata) {
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

    var body = buildCaseHandoverGrantedBody(newAdvisorName, description, metadata);
    if (body == null) {
      return;
    }
    var roomId = matrixRoomId;
    resolveMatrixCredentialsPreferConsultant(session)
        .ifPresent(credentials -> sendSystemMessage(session.getId(), roomId, body, credentials));
  }

  /**
   * Posts the Erstantwort — the whole Baustein sequence as <b>one</b> persisted event (ADR-018,
   * ORISO-UserService#926).
   *
   * <p><b>No Carimat account is created.</b> Carimat is a rendering identity, not a Matrix account
   * (ADR-018 §3): a bot in the room would be an additional Megolm key holder in a room carrying §11
   * KDG special-category data, and a bot has no Schweigepflicht. The event goes out through the
   * existing credential resolution, exactly like every other system message here, and adds no room
   * member.
   *
   * <p>A failure is logged and swallowed. The Erstantwort is important, but it must never take the
   * enquiry down with it — the person's message reaching a counsellor outranks the platform's own
   * greeting, and a thrown exception here would roll the dispatch back.
   *
   * @param session the session whose room receives the Erstantwort
   * @param body the full message body including the [SYSTEM_NOTIFICATION] prefix
   */
  public void postFirstResponseMessage(Session session, String body) {
    if (session == null || session.getId() == null || isBlank(body)) {
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
    var roomId = matrixRoomId;
    resolveMatrixCredentials(session)
        .ifPresent(credentials -> sendSystemMessage(session.getId(), roomId, body, credentials));
  }

  /**
   * Posts a prepared [SYSTEM_NOTIFICATION] body into the session's room (FE#1876).
   *
   * @param session the session whose room receives the notice
   * @param body the full body including the prefix
   * @param asConsultant post strictly as the assigned consultant, never as anyone else; false uses
   *     the usual asker-first resolution
   * @return true when Matrix accepted the event
   */
  public boolean postSystemMessage(Session session, String body, boolean asConsultant) {
    if (session == null || session.getId() == null || isBlank(body)) {
      return false;
    }
    var matrixRoomId = session.getMatrixRoomId();
    if (isBlank(matrixRoomId)) {
      matrixRoomId =
          sessionService.getSession(session.getId()).map(Session::getMatrixRoomId).orElse(null);
    }
    if (isBlank(matrixRoomId)) {
      return false;
    }
    var roomId = matrixRoomId;
    var credentials =
        asConsultant ? consultantMatrixCredentials(session) : resolveMatrixCredentials(session);
    return credentials
        .map(value -> sendSystemMessage(session.getId(), roomId, body, value))
        .orElse(false);
  }

  private boolean sendSystemMessage(
      Long sessionId, String matrixRoomId, String body, MatrixCredentials credentials) {
    var accessToken = credentials.accessToken(matrixSynapseService);
    if (accessToken == null) {
      log.warn(
          "Skipping Matrix system message for session {} — token unavailable for {}",
          sessionId,
          credentials.principal());
      return false;
    }
    var response = matrixSynapseService.sendMessage(matrixRoomId, body, accessToken);
    if (response != null && response.containsKey("error")) {
      log.warn("Matrix system message for session {} failed: {}", sessionId, response.get("error"));
      return false;
    }
    return true;
  }

  /** Whether the assigned consultant has a Matrix account to post with (FE#1876). */
  public boolean hasConsultantMatrixIdentity(Session session) {
    return session != null && consultantMatrixCredentials(session).isPresent();
  }

  private Optional<MatrixCredentials> resolveMatrixCredentialsPreferConsultant(Session session) {
    return consultantMatrixCredentials(session).or(() -> resolveMatrixCredentials(session));
  }

  private Optional<MatrixCredentials> consultantMatrixCredentials(Session session) {
    Consultant consultant = session.getConsultant();
    if (consultant != null && isNotBlank(consultant.getId())) {
      consultant = consultantService.getConsultant(consultant.getId()).orElse(consultant);
      if (isNotBlank(consultant.getMatrixUserId())) {
        return Optional.of(MatrixCredentials.forMatrixUser(consultant.getMatrixUserId()));
      }
    }
    return Optional.empty();
  }

  private String buildCaseHandoverGrantedBody(
      String newAdvisorName, String description, GrantedAccessMetadata metadata) {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("type", CASE_HANDOVER_GRANTED_TYPE);
    if (isNotBlank(newAdvisorName)) {
      payload.put("username", newAdvisorName);
    }
    if (isNotBlank(description)) {
      payload.put("description", description);
    }
    if (metadata != null
        && metadata.requestId() != null
        && metadata.requestId() > 0
        && metadata.clientConsent() != null
        && metadata.accessType() != null) {
      payload.put("handover", metadata);
    }
    try {
      return SYSTEM_NOTIFICATION_PREFIX + OBJECT_MAPPER.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      log.warn("Could not serialize case-handover system message payload", exception);
      return null;
    }
  }

  private void sendUserLeftMessage(
      Long sessionId,
      String matrixRoomId,
      String displayUsername,
      EndedBy endedBy,
      MatrixCredentials credentials) {
    var accessToken = credentials.accessToken(matrixSynapseService);
    if (accessToken == null) {
      log.warn(
          "Skipping Matrix user-left message for session {} — token unavailable for {}",
          sessionId,
          credentials.principal());
      return;
    }

    var body = buildUserLeftChatBody(displayUsername, endedBy);
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
        .filter(dto -> isNotBlank(dto.getMatrixUserId()))
        .map(dto -> MatrixCredentials.forMatrixUser(dto.getMatrixUserId()));
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

  private String buildUserLeftChatBody(String username, EndedBy endedBy) {
    var safeUsername = username == null ? "" : username.replace("\\", "\\\\").replace("\"", "\\\"");
    return SYSTEM_NOTIFICATION_PREFIX
        + "{\"type\":\""
        + USER_LEFT_CHAT_TYPE
        + "\",\"username\":\""
        + safeUsername
        + "\""
        + (endedBy == null ? "" : ",\"endedBy\":\"" + endedBy.name() + "\"")
        + "}";
  }

  private static final class MatrixCredentials {
    private final String matrixUserId;

    private MatrixCredentials(String matrixUserId) {
      this.matrixUserId = matrixUserId;
    }

    private static MatrixCredentials forMatrixUser(String matrixUserId) {
      return new MatrixCredentials(matrixUserId);
    }

    private String accessToken(MatrixSynapseService matrixSynapseService) {
      return matrixSynapseService.loginAsUserAccessToken(matrixUserId);
    }

    private String principal() {
      return matrixUserId;
    }
  }
}
