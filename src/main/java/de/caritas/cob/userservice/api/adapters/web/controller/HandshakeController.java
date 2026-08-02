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
 * Support-access request endpoints (ADR-018 §1). Identities always come from the token: the
 * initiator is the authenticated caller of {@code POST /requests}, the counterpart is the
 * authenticated caller of {@code confirm} and {@code decline} — a request can never be raised or
 * decided in someone else's name. Fresh-credential verification, the agency check, and the
 * per-purpose role checks live in {@link HandshakeService}.
 *
 * <p>Confirmation answers {@code 202 Accepted}: the room does not exist yet at that moment, it is
 * provisioned asynchronously, and the UI must show that rather than claim an active session.
 */
@RestController
@RequestMapping("/users/support-access/requests")
@RequiredArgsConstructor
public class HandshakeController {

  private final @NonNull HandshakeService handshakeService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @PostMapping
  public ResponseEntity<HandshakeItem> initiate(
      @jakarta.validation.Valid @RequestBody InitiateHandshakeRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(handshakeService.initiate(authenticatedUser, request));
  }

  @PostMapping("/{handshakeId}/confirm")
  public ResponseEntity<HandshakeItem> confirm(
      @PathVariable String handshakeId,
      @jakarta.validation.Valid @RequestBody ConfirmHandshakeRequest request) {
    return ResponseEntity.accepted()
        .body(
            handshakeService.confirm(
                authenticatedUser, handshakeId, request.getPassword(), request.getOtp()));
  }

  @PostMapping("/{handshakeId}/decline")
  public ResponseEntity<HandshakeItem> decline(@PathVariable String handshakeId) {
    return ResponseEntity.ok(handshakeService.decline(authenticatedUser, handshakeId));
  }

  @GetMapping("/pending")
  public ResponseEntity<List<HandshakeItem>> pending() {
    return ResponseEntity.ok(handshakeService.pendingForCounterpart(authenticatedUser));
  }

  @Getter
  @Setter
  public static class ConfirmHandshakeRequest {
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 255)
    private String password;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 16)
    private String otp;

    @Override
    public String toString() {
      return "ConfirmHandshakeRequest{password=[REDACTED], otp=[REDACTED]}";
    }
  }
}
