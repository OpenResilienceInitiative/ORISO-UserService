package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.identity.GuestIdentitySuggestion;
import de.caritas.cob.userservice.api.service.identity.GuestIdentitySuggestionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class IdentitySuggestionController {
  private final GuestIdentitySuggestionService service;

  @PostMapping("/users/identity-suggestions")
  public ResponseEntity<List<GuestIdentitySuggestion>> suggest(
      @RequestBody(required = false) SuggestionRequest request) {
    if (request == null || request.count() == null)
      throw new BadRequestException("Suggestion request is required");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.suggest(request.locale(), request.count(), request.exclude()));
  }

  public record SuggestionRequest(String locale, Integer count, List<String> exclude) {}
}
