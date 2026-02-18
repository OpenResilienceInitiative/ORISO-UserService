package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.matrix.MatrixEventListenerService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for Matrix session synchronization. Allows frontend to register Matrix rooms for
 * real-time event notifications via LiveService.
 */
@RestController
@RequestMapping("/matrix/sync")
@RequiredArgsConstructor
@Slf4j
public class MatrixSyncController {

  private final @NonNull MatrixEventListenerService matrixEventListenerService;
  private final @NonNull SessionService sessionService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /**
   * Register a Matrix room for real-time event listening. When messages arrive in this room, the
   * backend will trigger LiveService events to notify connected users.
   *
   * @param sessionId the session ID
   * @return success response
   */
  @PostMapping("/register/{sessionId}")
  public ResponseEntity<?> registerRoomForSync(@PathVariable Long sessionId) {

    try {
      log.info("📡 Registering Matrix room for session {}", sessionId);

      var session = sessionService.getSession(sessionId);
      if (session.isEmpty() || session.get().getMatrixRoomId() == null) {
        log.error("Session {} not found or has no Matrix room", sessionId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Session not found or has no Matrix room"));
      }

      String matrixRoomId = session.get().getMatrixRoomId();
      String userId = session.get().getUser().getUserId();
      String consultantId =
          session.get().getConsultant() != null ? session.get().getConsultant().getId() : null;

      // Build set of user IDs who should receive notifications
      Set<String> userIds = new HashSet<>();
      userIds.add(userId);
      if (consultantId != null) {
        userIds.add(consultantId);
      }

      // Register room with MatrixEventListenerService
      matrixEventListenerService.registerRoom(sessionId, matrixRoomId, userIds);

      log.info(
          "✅ Registered Matrix room {} for session {} with {} users",
          matrixRoomId,
          sessionId,
          userIds.size());

      return ResponseEntity.ok(
          Map.of("success", true, "roomId", matrixRoomId, "userCount", userIds.size()));

    } catch (Exception e) {
      log.error("❌ Error registering Matrix room for sync", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Internal server error: " + e.getMessage()));
    }
  }

  /**
   * Unregister a Matrix room from event listening (when session is closed).
   *
   * @param sessionId the session ID
   * @return success response
   */
  @DeleteMapping("/register/{sessionId}")
  public ResponseEntity<?> unregisterRoomFromSync(@PathVariable Long sessionId) {

    try {
      var session = sessionService.getSession(sessionId);
      if (session.isEmpty() || session.get().getMatrixRoomId() == null) {
        return ResponseEntity.ok(Map.of("success", true));
      }

      String matrixRoomId = session.get().getMatrixRoomId();
      matrixEventListenerService.unregisterRoom(matrixRoomId);

      log.info("✅ Unregistered Matrix room {} for session {}", matrixRoomId, sessionId);

      return ResponseEntity.ok(Map.of("success", true));

    } catch (Exception e) {
      log.error("❌ Error unregistering Matrix room", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Internal server error"));
    }
  }
}
