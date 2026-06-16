package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.matrix.RedisMessageMirrorService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Controller for Matrix messaging endpoints. */
@RestController
@RequestMapping("/matrix")
@RequiredArgsConstructor
@Slf4j
public class MatrixMessageController {

  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionService sessionService;
  private final @NonNull ChatService chatService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull UserService userService;
  private final @NonNull AgencyMatrixCredentialClient matrixCredentialClient;
  private final @NonNull RedisMessageMirrorService redisMessageMirrorService;

  /**
   * Send a message to a Matrix room.
   *
   * @param sessionId the session ID
   * @param messageRequest the message content
   * @return the message send response
   */
  @PostMapping("/sessions/{sessionId}/messages")
  public ResponseEntity<?> sendMessage(
      @PathVariable Long sessionId, @RequestBody Map<String, Object> messageRequest) {

    try {
      var session = sessionService.getSession(sessionId);
      if (session.isEmpty() || session.get().getMatrixRoomId() == null) {
        log.error("Session {} not found or has no Matrix room", sessionId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Session not found or has no Matrix room"));
      }

      String keycloakUserId = authenticatedUser.getUserId();
      String keycloakUsername = authenticatedUser.getUsername();

      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      Optional<String> accessTokenOpt =
          isConsultant
              ? obtainConsultantAccessToken(keycloakUserId)
              : obtainUserAccessToken(session.get().getUser());

      if (accessTokenOpt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Matrix login failed"));
      }

      String accessToken = accessTokenOpt.get();
      String matrixUsername = extractMatrixUsernameForLog(isConsultant, keycloakUserId, session.get().getUser());

      log.info(
          "Sending: {} (role: {}) → Matrix: {}",
          keycloakUsername,
          isConsultant ? "consultant" : "user",
          matrixUsername);

      String message = (String) messageRequest.get("message");
      String roomId = session.get().getMatrixRoomId();

      var response = matrixSynapseService.sendMessage(roomId, message, accessToken);
      Object eventId = response != null ? response.get("event_id") : null;

      redisMessageMirrorService.mirrorOutgoingMessage(
          sessionId,
          roomId,
          keycloakUsername,
          isConsultant,
          message,
          eventId == null ? null : String.valueOf(eventId));

      log.info("Message sent to room {} by {}", roomId, matrixUsername);
      return ResponseEntity.ok(Map.of("success", true));

    } catch (Exception e) {
      log.error("Error sending message", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Internal server error"));
    }
  }

  /**
   * Get messages from a Matrix room.
   *
   * @param sessionId the session ID
   * @return the messages
   */
  @GetMapping("/sessions/{sessionId}/messages")
  public ResponseEntity<?> getMessages(@PathVariable Long sessionId) {

    try {
      String matrixRoomId = null;
      var session = sessionService.getSession(sessionId);
      if (session.isPresent() && session.get().getMatrixRoomId() != null) {
        matrixRoomId = session.get().getMatrixRoomId();
      } else {
        var chat = chatService.getChat(sessionId);
        if (chat.isPresent() && chat.get().getMatrixRoomId() != null) {
          matrixRoomId = chat.get().getMatrixRoomId();
        }
      }

      if (matrixRoomId == null) {
        log.error("Session/Chat {} not found or has no Matrix room", sessionId);
        return ResponseEntity.ok(Map.of("messages", new Object[0]));
      }

      String keycloakUserId = authenticatedUser.getUserId();
      String keycloakUsername = authenticatedUser.getUsername();

      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      Optional<String> accessTokenOpt =
          resolveReadAccessToken(isConsultant, keycloakUserId, session);

      if (accessTokenOpt.isEmpty()) {
        return ResponseEntity.ok(Map.of("messages", new Object[0]));
      }

      log.info("Fetching messages for {}", keycloakUsername);

      var messages = matrixSynapseService.getRoomMessages(matrixRoomId, accessTokenOpt.get());

      log.info("Retrieved {} messages from room {}", messages.size(), matrixRoomId);

      return ResponseEntity.ok(Map.of("success", true, "messages", messages));

    } catch (Exception e) {
      log.error("Error getting messages", e);
      return ResponseEntity.ok(Map.of("messages", new Object[0]));
    }
  }

  /**
   * Sync with Matrix to get new messages (long-polling for real-time updates).
   *
   * @param sessionId the session ID
   * @return the new messages
   */
  @GetMapping("/sessions/{sessionId}/sync")
  public ResponseEntity<?> syncMessages(@PathVariable Long sessionId) {

    try {
      var session = sessionService.getSession(sessionId);
      if (session.isEmpty() || session.get().getMatrixRoomId() == null) {
        return ResponseEntity.ok(Map.of("messages", new Object[0]));
      }

      String keycloakUserId = authenticatedUser.getUserId();

      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      Optional<String> accessTokenOpt =
          isConsultant
              ? obtainConsultantAccessToken(keycloakUserId)
              : obtainUserAccessToken(session.get().getUser());

      if (accessTokenOpt.isEmpty()) {
        return ResponseEntity.ok(Map.of("messages", new Object[0]));
      }

      String roomId = session.get().getMatrixRoomId();
      String matrixUsername =
          isConsultant
              ? extractMatrixUsername(
                  consultantService
                      .getConsultant(keycloakUserId)
                      .map(Consultant::getMatrixUserId)
                      .orElse(null))
              : extractMatrixUsername(session.get().getUser().getMatrixUserId());

      var syncResult =
          matrixSynapseService.syncRoom(roomId, accessTokenOpt.get(), matrixUsername, 30000);

      return ResponseEntity.ok(syncResult);

    } catch (Exception e) {
      return ResponseEntity.ok(Map.of("messages", new Object[0]));
    }
  }

  /**
   * Upload a file to Matrix media repository.
   *
   * @param sessionId the session ID
   * @param file the file to upload
   * @return the Matrix content URI
   */
  @PostMapping("/sessions/{sessionId}/upload")
  public ResponseEntity<?> uploadFile(
      @PathVariable Long sessionId, @RequestParam("file") MultipartFile file) {

    try {
      log.info("Upload request for session {}, file: {}", sessionId, file.getOriginalFilename());

      String matrixRoomId = null;
      User sessionUser = null;

      var session = sessionService.getSession(sessionId);
      if (session.isPresent() && session.get().getMatrixRoomId() != null) {
        matrixRoomId = session.get().getMatrixRoomId();
        sessionUser = session.get().getUser();
        log.info("Upload: Found 1-on-1 session with Matrix room: {}", matrixRoomId);
      } else {
        var chat = chatService.getChat(sessionId);
        if (chat.isPresent() && chat.get().getMatrixRoomId() != null) {
          matrixRoomId = chat.get().getMatrixRoomId();
          log.info("Upload: Found group chat with Matrix room: {}", matrixRoomId);
        }
      }

      if (matrixRoomId == null) {
        log.error("Session/Chat {} not found or has no Matrix room", sessionId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Session not found or has no Matrix room"));
      }

      String keycloakUserId = authenticatedUser.getUserId();
      String keycloakUsername = authenticatedUser.getUsername();

      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      Optional<String> accessTokenOpt;
      if (isConsultant) {
        accessTokenOpt = obtainConsultantAccessToken(keycloakUserId);
      } else {
        if (sessionUser == null) {
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "User session not found"));
        }
        accessTokenOpt = obtainUserAccessToken(sessionUser);
      }

      if (accessTokenOpt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Matrix login failed"));
      }

      log.info("Upload: {} → Matrix room {}", keycloakUsername, matrixRoomId);

      java.util.Map<String, Object> result =
          matrixSynapseService.uploadFile(file, matrixRoomId, accessTokenOpt.get());

      log.info("File uploaded and message sent successfully");

      return ResponseEntity.ok(result);

    } catch (Exception e) {
      log.error("Error uploading file", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Internal server error: " + e.getMessage()));
    }
  }

  /**
   * Download a file from Matrix media repository. Uses admin access token since Matrix media should
   * be accessible to authenticated users.
   *
   * @param serverName the Matrix server name
   * @param mediaId the media ID
   * @return the file bytes
   */
  @GetMapping("/media/download/{serverName}/{mediaId}")
  public ResponseEntity<?> downloadFile(
      @PathVariable String serverName, @PathVariable String mediaId) {

    try {
      log.info(
          "Download request for media: {}/{} by user: {}",
          serverName,
          mediaId,
          authenticatedUser.getUsername());

      String adminToken = matrixSynapseService.getAdminToken();
      if (adminToken == null) {
        log.error("Failed to get admin token for download");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
      }

      byte[] fileBytes = matrixSynapseService.downloadFile(serverName, mediaId, adminToken);

      log.info("File downloaded: {} bytes", fileBytes.length);

      return ResponseEntity.ok()
          .header("Content-Disposition", "attachment")
          .header("Content-Type", "application/octet-stream")
          .body(fileBytes);

    } catch (Exception e) {
      log.error("Error downloading file", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  private Optional<String> resolveReadAccessToken(
      boolean isConsultant, String keycloakUserId, Optional<de.caritas.cob.userservice.api.model.Session> session) {
    if (isConsultant) {
      if (session.isPresent() && session.get().getConsultant() == null) {
        Long agencyId = session.get().getAgencyId();
        if (agencyId == null) {
          return Optional.empty();
        }
        return obtainAgencyAccessToken(agencyId);
      }
      return obtainConsultantAccessToken(keycloakUserId);
    }

    if (session.isEmpty()) {
      return Optional.empty();
    }
    return obtainUserAccessToken(session.get().getUser());
  }

  private Optional<String> obtainConsultantAccessToken(String keycloakUserId) {
    var consultant = consultantService.getConsultant(keycloakUserId);
    if (consultant.isEmpty()) {
      log.error("Consultant not found for keycloak user {}", keycloakUserId);
      return Optional.empty();
    }
    String matrixUserId = consultant.get().getMatrixUserId();
    if (matrixUserId == null || !matrixUserId.startsWith("@")) {
      log.error("Consultant {} missing Matrix ID", keycloakUserId);
      return Optional.empty();
    }
    return Optional.ofNullable(matrixSynapseService.loginUserViaAdmin(matrixUserId));
  }

  private Optional<String> obtainUserAccessToken(User user) {
    if (user == null) {
      return Optional.empty();
    }
    String matrixUserId = user.getMatrixUserId();
    if (matrixUserId == null || !matrixUserId.startsWith("@")) {
      log.error("User {} missing Matrix ID", user.getUserId());
      return Optional.empty();
    }
    return Optional.ofNullable(matrixSynapseService.loginUserViaAdmin(matrixUserId));
  }

  private Optional<String> obtainAgencyAccessToken(Long agencyId) {
    var agencyCredentials = matrixCredentialClient.fetchMatrixCredentials(agencyId);
    if (agencyCredentials.isEmpty()) {
      log.warn("No Matrix credentials for agency {}", agencyId);
      return Optional.empty();
    }
    AgencyMatrixCredentialsDTO credentials = agencyCredentials.get();
    String agencyMatrixUsername = extractMatrixUsername(credentials.getMatrixUserId());
    String password = credentials.getMatrixPassword();
    if (agencyMatrixUsername == null || password == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(matrixSynapseService.loginUser(agencyMatrixUsername, password));
  }

  private String extractMatrixUsernameForLog(
      boolean isConsultant, String keycloakUserId, User sessionUser) {
    if (isConsultant) {
      return consultantService
          .getConsultant(keycloakUserId)
          .map(Consultant::getMatrixUserId)
          .map(this::extractMatrixUsername)
          .orElse("unknown");
    }
    return extractMatrixUsername(sessionUser != null ? sessionUser.getMatrixUserId() : null);
  }

  private String extractMatrixUsername(String matrixUserId) {
    if (matrixUserId != null && matrixUserId.startsWith("@")) {
      return matrixUserId.substring(1).split(":")[0];
    }
    return null;
  }
}
