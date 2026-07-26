package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import java.time.LocalDateTime;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global per-user Do-Not-Disturb (decided 2026-07-18). Authoritative cross-device store; the
 * frontend reads it to suppress announcements while active and notification emails are gated on it.
 * A {@code null} {@code dndUntil} clears DND.
 */
@RestController
@RequestMapping("/users/notifications/do-not-disturb")
@RequiredArgsConstructor
public class DoNotDisturbController {

  private final @NonNull DoNotDisturbService doNotDisturbService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping
  public ResponseEntity<DoNotDisturbDTO> get() {
    return ResponseEntity.ok(
        new DoNotDisturbDTO(doNotDisturbService.getDndUntil(authenticatedUser.getUserId())));
  }

  @PutMapping
  public ResponseEntity<DoNotDisturbDTO> put(@RequestBody DoNotDisturbDTO request) {
    LocalDateTime until = request != null ? request.dndUntil() : null;
    doNotDisturbService.setDndUntil(authenticatedUser.getUserId(), until);
    return ResponseEntity.ok(new DoNotDisturbDTO(until));
  }

  public record DoNotDisturbDTO(LocalDateTime dndUntil) {}
}
