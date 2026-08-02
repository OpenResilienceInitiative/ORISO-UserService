package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.support.SupportAccessSessionService;
import de.caritas.cob.userservice.api.service.support.SupportAccessSessionService.SupportAccessSessionItem;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary Support Access sessions (ADR-018 §4). Listing is always scoped to the caller, only the
 * session's Berater*in may terminate early, and only a participant may register the call room —
 * enforcement lives in {@link SupportAccessSessionService}.
 */
@RestController
@RequestMapping("/users/support-access/sessions")
@RequiredArgsConstructor
public class SupportAccessSessionController {

  private final @NonNull SupportAccessSessionService supportAccessSessionService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping("/active")
  public ResponseEntity<List<SupportAccessSessionItem>> active() {
    return ResponseEntity.ok(supportAccessSessionService.activeFor(authenticatedUser));
  }

  @PostMapping("/{sessionId}/terminate")
  public ResponseEntity<Void> terminate(@PathVariable String sessionId) {
    supportAccessSessionService.terminate(authenticatedUser, sessionId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Element Call reports the encrypted media room it created, so the four-hour withdrawal can close
   * the signalling room and the media room rather than only the one the backend knows about.
   */
  @PutMapping("/{sessionId}/call-room")
  public ResponseEntity<Void> registerCallRoom(
      @PathVariable String sessionId,
      @jakarta.validation.Valid @RequestBody RegisterCallRoomRequest request) {
    supportAccessSessionService.registerCallRoom(
        authenticatedUser, sessionId, request.getCallRoomId());
    return ResponseEntity.noContent().build();
  }

  @Getter
  @Setter
  public static class RegisterCallRoomRequest {
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 255)
    private String callRoomId;
  }
}
