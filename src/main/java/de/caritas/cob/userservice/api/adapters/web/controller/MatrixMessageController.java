package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Map;
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

      // Check Keycloak roles - SIMPLE and RELIABLE!
      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      String matrixUsername;
      String password;

      if (isConsultant) {
        // CONSULTANT
        var consultant = consultantService.getConsultant(keycloakUserId);
        if (consultant.isEmpty()) {
          log.error("Consultant {} not found", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Consultant not found"));
        }

        String matrixId = consultant.get().getMatrixUserId();
        if (matrixId != null && matrixId.startsWith("@")) {
          matrixUsername = matrixId.substring(1).split(":")[0];
        } else {
          log.error("Consultant {} missing Matrix ID", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Matrix ID not configured"));
        }

        password = consultant.get().getMatrixPassword();
        if (password == null) {
          log.error("Consultant {} missing password", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Password not configured"));
        }
      } else {
        // USER - Keep old working logic!
        var user = session.get().getUser();

        String matrixId = user.getMatrixUserId();
        if (matrixId != null && matrixId.startsWith("@")) {
          matrixUsername = matrixId.substring(1).split(":")[0];
        } else {
          log.error("User {} missing Matrix ID", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Matrix ID not configured"));
        }

        password = user.getMatrixPassword();
        if (password == null) {
          log.error("User {} missing password", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Password not configured"));
        }
      }

      log.info(
          "Sending: {} (role: {}) → Matrix: {}",
          keycloakUsername,
          isConsultant ? "consultant" : "user",
          matrixUsername);

      String accessToken = matrixSynapseService.loginUser(matrixUsername, password);
      if (accessToken == null) {
        log.error("Matrix login failed for {}", matrixUsername);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Matrix login failed"));
      }

      String message = (String) messageRequest.get("message");
      String roomId = session.get().getMatrixRoomId();

      var response = matrixSynapseService.sendMessage(roomId, message, accessToken);

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
      // MATRIX MIGRATION: Check both Session (1-on-1) and Chat (group chats)
      String matrixRoomId = null;

      // Try Session first (1-on-1 chats)
      var session = sessionService.getSession(sessionId);
      if (session.isPresent() && session.get().getMatrixRoomId() != null) {
        matrixRoomId = session.get().getMatrixRoomId();
      } else {
        // Try Chat (group chats)
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

      // Check Keycloak roles
      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      String matrixUsername;
      String password;

      if (isConsultant) {
        var consultant = consultantService.getConsultant(keycloakUserId);
        if (consultant.isEmpty()) {
          return ResponseEntity.ok(Map.of("messages", new Object[0]));
        }

        // For group chats or accepted sessions, use consultant's own Matrix credentials
        // For enquiries (NEW status, no consultant assigned), use agency's Matrix credentials
        if (session.isPresent() && session.get().getConsultant() == null) {
          // This is an enquiry - use agency's Matrix service account
          Long agencyId = session.get().getAgencyId();
          if (agencyId == null) {
            log.warn("Session {} has no agency ID", sessionId);
            return ResponseEntity.ok(Map.of("messages", new Object[0]));
          }

          var agencyCredentials = matrixCredentialClient.fetchMatrixCredentials(agencyId);
          if (agencyCredentials.isEmpty()) {
            log.warn("No Matrix credentials for agency {}", agencyId);
            return ResponseEntity.ok(Map.of("messages", new Object[0]));
          }

          String agencyMatrixId = agencyCredentials.get().getMatrixUserId();
          if (agencyMatrixId != null && agencyMatrixId.startsWith("@")) {
            matrixUsername = agencyMatrixId.substring(1).split(":")[0];
          } else {
            return ResponseEntity.ok(Map.of("messages", new Object[0]));
          }

          password = agencyCredentials.get().getMatrixPassword();
          if (password == null) {
            return ResponseEntity.ok(Map.of("messages", new Object[0]));
          }
        } else {
          // This is a group chat or accepted session - use consultant's own Matrix credentials
          String matrixId = consultant.get().getMatrixUserId();
          if (matrixId != null && matrixId.startsWith("@")) {
            matrixUsername = matrixId.substring(1).split(":")[0];
          } else {
            return ResponseEntity.ok(Map.of("messages", new Object[0]));
          }

          password = consultant.get().getMatrixPassword();
          if (password == null) {
            return ResponseEntity.ok(Map.of("messages", new Object[0]));
          }
        }
      } else {
        // USER - only for 1-on-1 sessions
        if (session.isEmpty()) {
          log.error("User trying to access group chat {} - not allowed", sessionId);
          return ResponseEntity.ok(Map.of("messages", new Object[0]));
        }

        var user = session.get().getUser();

        String matrixId = user.getMatrixUserId();
        if (matrixId != null && matrixId.startsWith("@")) {
          matrixUsername = matrixId.substring(1).split(":")[0];
        } else {
          return ResponseEntity.ok(Map.of("messages", new Object[0]));
        }

        password = user.getMatrixPassword();
        if (password == null) {
          return ResponseEntity.ok(Map.of("messages", new Object[0]));
        }
      }

      log.info("Fetching messages: {} → {}", keycloakUsername, matrixUsername);

      String accessToken = matrixSynapseService.loginUser(matrixUsername, password);
      if (accessToken == null) {
        return ResponseEntity.ok(Map.of("messages", new Object[0]));
      }

      var messages = matrixSynapseService.getRoomMessages(matrixRoomId, accessToken);

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
      String username = authenticatedUser.getUsername();

      // Check Keycloak roles
      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      String password;

      if (isConsultant) {
        var consultant = consultantService.getConsultant(keycloakUserId);
        if (consultant.isEmpty() || consultant.get().getMatrixPassword() == null) {
          return ResponseEntity.ok(Map.of("messages", new Object[0]));
        }
        password = consultant.get().getMatrixPassword();
      } else {
        var user = session.get().getUser();
        if (user.getMatrixPassword() == null) {
          return ResponseEntity.ok(Map.of("messages", new Object[0]));
        }
        password = user.getMatrixPassword();
      }

      String accessToken = matrixSynapseService.loginUser(username, password);
      if (accessToken == null) {
        return ResponseEntity.ok(Map.of("messages", new Object[0]));
      }

      String roomId = session.get().getMatrixRoomId();

      // Use 30-second timeout for long-polling
      var syncResult = matrixSynapseService.syncRoom(roomId, accessToken, username, 30000);

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
      log.info("📤 Upload request for session {}, file: {}", sessionId, file.getOriginalFilename());

      // MATRIX MIGRATION: Check both Session (1-on-1) and Chat (group chats)
      String matrixRoomId = null;
      boolean isGroupChat = false;
      de.caritas.cob.userservice.api.model.User sessionUser = null;

      var session = sessionService.getSession(sessionId);
      if (session.isPresent() && session.get().getMatrixRoomId() != null) {
        matrixRoomId = session.get().getMatrixRoomId();
        sessionUser = session.get().getUser();
        log.info("📤 Upload: Found 1-on-1 session with Matrix room: {}", matrixRoomId);
      } else {
        var chat = chatService.getChat(sessionId);
        if (chat.isPresent() && chat.get().getMatrixRoomId() != null) {
          matrixRoomId = chat.get().getMatrixRoomId();
          isGroupChat = true;
          log.info("📤 Upload: Found group chat with Matrix room: {}", matrixRoomId);
        }
      }

      if (matrixRoomId == null) {
        log.error("Session/Chat {} not found or has no Matrix room", sessionId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Session not found or has no Matrix room"));
      }

      String keycloakUserId = authenticatedUser.getUserId();
      String keycloakUsername = authenticatedUser.getUsername();

      // Check Keycloak roles
      boolean isConsultant =
          authenticatedUser.getRoles() != null
              && authenticatedUser.getRoles().contains("consultant");

      String matrixUsername;
      String password;

      if (isConsultant) {
        // MATRIX MIGRATION: For group chats, consultants use their own credentials
        var consultant = consultantService.getConsultant(keycloakUserId);
        if (consultant.isEmpty()) {
          log.error("Consultant {} not found", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Consultant not found"));
        }

        String matrixId = consultant.get().getMatrixUserId();
        if (matrixId != null && matrixId.startsWith("@")) {
          matrixUsername = matrixId.substring(1).split(":")[0];
        } else {
          log.error("Consultant {} missing Matrix ID", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Matrix ID not configured"));
        }

        password = consultant.get().getMatrixPassword();
        if (password == null) {
          log.error("Consultant {} missing password", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Password not configured"));
        }
      } else {
        // USER (only for 1-on-1 sessions)
        if (sessionUser == null) {
          log.error("User session not found for upload");
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "User session not found"));
        }

        String matrixId = sessionUser.getMatrixUserId();
        if (matrixId != null && matrixId.startsWith("@")) {
          matrixUsername = matrixId.substring(1).split(":")[0];
        } else {
          log.error("User {} missing Matrix ID", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Matrix ID not configured"));
        }

        password = sessionUser.getMatrixPassword();
        if (password == null) {
          log.error("User {} missing password", keycloakUsername);
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(Map.of("error", "Password not configured"));
        }
      }

      log.info("Upload: {} → Matrix: {}", keycloakUsername, matrixUsername);

      String accessToken = matrixSynapseService.loginUser(matrixUsername, password);
      if (accessToken == null) {
        log.error("Matrix login failed for {}", matrixUsername);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Matrix login failed"));
      }

      String roomId = matrixRoomId;

      // Upload file to Matrix and automatically send as message
      java.util.Map<String, Object> result =
          matrixSynapseService.uploadFile(file, roomId, accessToken);

      log.info("✅ File uploaded and message sent successfully");

      return ResponseEntity.ok(result);

    } catch (Exception e) {
      log.error("❌ Error uploading file", e);
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
          "📥 Download request for media: {}/{} by user: {}",
          serverName,
          mediaId,
          authenticatedUser.getUsername());

      // Use admin token for downloads (Matrix media is accessible to all authenticated users)
      String adminToken = matrixSynapseService.getAdminToken();
      if (adminToken == null) {
        log.error("❌ Failed to get admin token for download");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
      }

      // Download file from Matrix
      byte[] fileBytes = matrixSynapseService.downloadFile(serverName, mediaId, adminToken);

      log.info("✅ File downloaded: {} bytes", fileBytes.length);

      return ResponseEntity.ok()
          .header("Content-Disposition", "attachment")
          .header("Content-Type", "application/octet-stream")
          .body(fileBytes);

    } catch (Exception e) {
      log.error("❌ Error downloading file", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
