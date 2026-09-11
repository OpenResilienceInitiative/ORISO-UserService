package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/matrix/calls", "/service/matrix/calls"})
@RequiredArgsConstructor
public class MatrixCallStateController {
  private final MatrixCallStateService states;
  private final AuthenticatedUser authenticatedUser;

  @GetMapping("/state")
  public ResponseEntity<?> getState(
      @RequestParam String sourceRoomId, @RequestParam String callId) {
    return states
        .read(authenticatedUser, sourceRoomId, callId)
        .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
