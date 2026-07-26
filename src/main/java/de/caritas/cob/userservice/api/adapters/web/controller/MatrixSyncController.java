package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Compatibility controller for Matrix session synchronization. Registration authorizes the caller
 * and heals technical-admin room membership; listener routing is derived from canonical session
 * data rather than request-local state.
 */
@RestController
@RequestMapping({"/matrix/sync", "/service/matrix/sync"})
@RequiredArgsConstructor
@Slf4j
public class MatrixSyncController {

  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull SessionService sessionService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  /**
   * Prepare a Matrix room for real-time event listening. When messages arrive in this room, the
   * backend resolves its current session context from the database and triggers LiveService events.
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
    var session = sessionService.assertUserHasAccess(sessionId, authenticatedUser);

    try {
      log.info("📡 Registering Matrix room for session {}", sessionId);

      if (session.getMatrixRoomId() == null) {
        log.error("Session {} has no Matrix room", sessionId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Session not found or has no Matrix room"));
      }

      String matrixRoomId = session.getMatrixRoomId();
      int participantCount =
          session.getConsultant() != null && session.getConsultant().getId() != null ? 2 : 1;

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

      log.info(
          "✅ Matrix room {} ready for sync for session {} with {} users",
          matrixRoomId,
          sessionId,
          participantCount);

      return ResponseEntity.ok(
          Map.of("success", true, "roomId", matrixRoomId, "userCount", participantCount));

    } catch (Exception e) {
      log.error("❌ Error registering Matrix room for sync", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Internal server error: " + e.getMessage()));
    }
  }

  /**
   * Acknowledge the legacy unregister call. Closing the canonical session controls whether the
   * listener can resolve the room; there is no process-local listener registration to tear down.
   *
   * @param sessionId the session ID
   * @return success response
   */
  @DeleteMapping("/register/{sessionId}")
  public ResponseEntity<?> unregisterRoomFromSync(@PathVariable Long sessionId) {

    // Authorize the caller against the session before unregistering, so an outsider cannot silently
    // kill live-event notifications for another session (denial-of-function). Kept outside the
    // try/catch so ForbiddenException / NotFoundException reach the global exception handler.
    var session = sessionService.assertUserHasAccess(sessionId, authenticatedUser);

    try {
      if (session.getMatrixRoomId() == null) {
        return ResponseEntity.ok(Map.of("success", true));
      }

      String matrixRoomId = session.getMatrixRoomId();

      log.info(
          "✅ Matrix sync unregister acknowledged for room {} and session {}",
          matrixRoomId,
          sessionId);

      return ResponseEntity.ok(Map.of("success", true));

    } catch (Exception e) {
      log.error("❌ Error unregistering Matrix room", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Internal server error"));
    }
  }
}
