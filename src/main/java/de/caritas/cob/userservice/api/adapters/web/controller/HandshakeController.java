package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService.HandshakeItem;
import de.caritas.cob.userservice.api.service.handshake.HandshakeService.InitiateHandshakeRequest;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live-Handshake endpoints (ADR-018 §1). Identities always come from the token: the initiator is
 * the authenticated caller of {@code POST /}, the counterpart is the authenticated caller of {@code
 * confirm} — a handshake can never be initiated or confirmed in someone else's name.
 * Fresh-credential verification and per-purpose role checks live in {@link HandshakeService}.
 */
@RestController
@RequestMapping("/users/handshakes")
@RequiredArgsConstructor
public class HandshakeController {

  private final @NonNull HandshakeService handshakeService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @PostMapping
  public ResponseEntity<HandshakeItem> initiate(@RequestBody InitiateHandshakeRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(handshakeService.initiate(authenticatedUser, request));
  }

  @PostMapping("/{handshakeId}/confirm")
  public ResponseEntity<HandshakeItem> confirm(
      @PathVariable String handshakeId, @RequestBody ConfirmHandshakeRequest request) {
    return ResponseEntity.ok(
        handshakeService.confirm(authenticatedUser, handshakeId, request.getPassword()));
  }

  @GetMapping("/pending")
  public ResponseEntity<List<HandshakeItem>> pending() {
    return ResponseEntity.ok(handshakeService.pendingForCounterpart(authenticatedUser));
  }

  @Getter
  @Setter
  public static class ConfirmHandshakeRequest {
    private String password;
  }
}
