package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.notification.RequestedContactSheetService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** A seeker explicitly requests one contact sheet for their own counselling session. */
@RestController
@RequiredArgsConstructor
public class RequestedContactSheetController {
  private final @NonNull RequestedContactSheetService contactSheets;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @PostMapping("/users/sessions/{sessionId}/contact-sheet-email")
  public ResponseEntity<Void> send(@PathVariable long sessionId) {
    contactSheets.send(sessionId, authenticatedUser.getUserId());
    return ResponseEntity.noContent().build();
  }
}
