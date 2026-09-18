package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.guestjoin.GuestJoinService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class GuestJoinController {
  private final GuestJoinService service;

  @PostMapping("/users/invitelinks/{token}/join")
  public ResponseEntity<GuestJoinService.JoinResponse> join(
      @PathVariable String token, @RequestBody(required = false) JoinRequest request) {
    if (request == null || request.languageFormal() == null) {
      throw new BadRequestException(
          "A guest selection, retry key and language preference are required");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            service.join(
                token,
                request.retryKey(),
                request.username(),
                request.avatarKey(),
                request.languageFormal()));
  }

  public record JoinRequest(
      String retryKey, String username, String avatarKey, Boolean languageFormal) {
    @Override
    public String toString() {
      return "GuestJoinRequest[redacted]";
    }
  }
}
