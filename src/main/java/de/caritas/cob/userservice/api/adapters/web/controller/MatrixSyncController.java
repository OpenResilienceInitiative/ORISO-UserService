package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.matrix.MatrixEventListenerService;
import de.caritas.cob.userservice.api.service.session.SessionAccessService;
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
 * real-time event notifications.
 */
@RestController
@RequestMapping({"/matrix/sync", "/service/matrix/sync"})
@RequiredArgsConstructor
@Slf4j
public class MatrixSyncController {

  private final @NonNull MatrixEventListenerService matrixEventListenerService;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionAccessService sessionAccessService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /**
   * Register a Matrix room for real-time event listening. When messages arrive in this room, the
   * backend will trigger notifications for the affected users.
   *
   * @param sessionId the session ID
   * @return success response
   */
  @PostMapping("/register/{sessionId}")
  public ResponseEntity<?> registerRoomForSync(@PathVariable Long sessionId) {

    // Authorize the authenticated caller against the session (asker owns it / consultant is
    // assigned) BEFORE doing anything. Kept outside the try/catch so the ForbiddenException /
    // NotFoundException propagate to the global exception handler (403/404) instead of being
    // swallowed into a 500. Closes the unauthenticated IDOR that leaked room ids + participant
    // counts and allowed anonymous state changes.
    var session = sessionAccessService.assertUserHasAccess(sessionId, authenticatedUser);

    try {
      log.info("📡 Registering Matrix room for session {}", sessionId);

      if (session.getMatrixRoomId() == null) {
        log.error("Session {} has no Matrix room", sessionId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Session not found or has no Matrix room"));
      }

      String matrixRoomId = session.getMatrixRoomId();
      String userId = session.getUser().getUserId();
      String consultantId =
          session.getConsultant() != null ? session.getConsultant().getId() : null;

      // Build set of user IDs who should receive notifications
      Set<String> userIds = new HashSet<>();
      userIds.add(userId);
      if (consultantId != null) {
        userIds.add(consultantId);
      }

      // The listener /sync loop runs as the technical admin and only receives events
      // for rooms the admin has joined — heal the membership on every registration so
      // message notifications can actually fire. Best-effort: registration must not
      // fail because of a transient Matrix problem.
      try {
        String memberMatrixUserId =
            session.getUser() != null ? session.getUser().getMatrixUserId() : null;
        if (!matrixSynapseService.ensureAdminInRoom(matrixRoomId, memberMatrixUserId)) {
          log.warn("Could not ensure Matrix admin membership in room {}", matrixRoomId);
        }
      } catch (Exception e) {
        log.warn(
            "Ensuring Matrix admin membership in room {} failed: {}", matrixRoomId, e.getMessage());
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

    // Authorize the caller against the session before unregistering, so an outsider cannot silently
    // kill live-event notifications for another session (denial-of-function). Kept outside the
    // try/catch so ForbiddenException / NotFoundException reach the global exception handler.
    var session = sessionAccessService.assertUserHasAccess(sessionId, authenticatedUser);

    try {
      if (session.getMatrixRoomId() == null) {
        return ResponseEntity.ok(Map.of("success", true));
      }

      String matrixRoomId = session.getMatrixRoomId();
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
