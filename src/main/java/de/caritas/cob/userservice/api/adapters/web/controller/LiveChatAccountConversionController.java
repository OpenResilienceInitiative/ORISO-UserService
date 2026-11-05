package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.livechat.LiveChatAccountConversionService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Establish durable sign-in for the caller's existing anonymous Live Chat account. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/service/users/live-chat-account")
public class LiveChatAccountConversionController {
  private final @NonNull LiveChatAccountConversionService service;
  private final @NonNull AuthenticatedUser authenticated;

  public record Request(
      String expectedUserId, String intentId, String password, Boolean recoveryKeySaved) {
    @Override
    public String toString() {
      return "LiveChatAccountConversionRequest[redacted]";
    }
  }

  public record Result(String username) {}

  public record StatusResult(String userId, String username, String state, String intentId) {}

  @GetMapping("/conversion")
  public ResponseEntity<StatusResult> status() {
    var status = service.status();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new StatusResult(
                status.userId(), authenticated.getUsername(), status.state(), status.intentId()));
  }

  @PostMapping("/conversion")
  public ResponseEntity<Result> convert(@RequestBody(required = false) Request request) {
    if (request == null
        || request.expectedUserId() == null
        || request.intentId() == null
        || request.password() == null
        || !Boolean.TRUE.equals(request.recoveryKeySaved())) {
      throw new BadRequestException("Conversion intent and password are required");
    }
    service.convert(request.expectedUserId(), request.intentId(), request.password());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new Result(authenticated.getUsername()));
  }
}
