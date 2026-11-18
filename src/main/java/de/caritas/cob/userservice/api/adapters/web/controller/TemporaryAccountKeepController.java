package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountKeepService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Keep account" (#1662): an advice seeker who joined with a temporary account through a link that
 * lets the guest choose turns that account into a permanent one by setting a password. It always
 * acts on the caller's own account; the user id comes from the token, never from the body.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/users/temporary-account/keep", "/service/users/temporary-account/keep"})
public class TemporaryAccountKeepController {

  private final @NonNull TemporaryAccountKeepService keepService;
  private final @NonNull AuthenticatedUser authenticatedUser;

  public record Request(String password) {
    @Override
    public String toString() {
      return "TemporaryAccountKeepRequest[redacted]";
    }
  }

  @PostMapping
  public ResponseEntity<Void> keep(@RequestBody(required = false) Request request) {
    if (request == null || request.password() == null || request.password().isBlank()) {
      throw new BadRequestException("A password is required");
    }
    keepService.keep(authenticatedUser.getUserId(), request.password());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
