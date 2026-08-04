package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.support.SupportRoomService;
import de.caritas.cob.userservice.api.service.support.SupportRoomService.SupportRoomItem;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary Support Access rooms (ADR-018 §2). Listing is always scoped to the caller; only the
 * room's Berater*in may terminate early — enforcement lives in {@link SupportRoomService}.
 */
@RestController
@RequestMapping("/users/support-rooms")
@RequiredArgsConstructor
public class SupportRoomController {

  private final @NonNull SupportRoomService supportRoomService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  @GetMapping("/active")
  public ResponseEntity<List<SupportRoomItem>> active() {
    return ResponseEntity.ok(supportRoomService.activeFor(authenticatedUser));
  }

  @PostMapping("/{roomId}/terminate")
  public ResponseEntity<Void> terminate(@PathVariable String roomId) {
    supportRoomService.terminate(authenticatedUser, roomId);
    return ResponseEntity.noContent().build();
  }
}
