package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestion;
import de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestionRequest;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.identity.GuestIdentitySuggestionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Serves the generated {@code suggestGuestIdentities} contract operation.
 *
 * <p>The operation has to be answered by the bean that implements {@code UsersApi}: the generated
 * interface maps the path itself and its default implementation replies {@code 501}. A separate
 * {@code @RestController} for the same path is shadowed for JSON requests, because the generated
 * mapping carries {@code consumes}/{@code produces} conditions and is therefore more specific.
 */
@Service
@RequiredArgsConstructor
public class IdentitySuggestionControllerDelegate {
  private final GuestIdentitySuggestionService service;

  public ResponseEntity<List<GuestIdentitySuggestion>> suggestGuestIdentities(
      GuestIdentitySuggestionRequest request) {
    if (request == null || request.getCount() == null) {
      throw new BadRequestException("Suggestion request is required");
    }
    var suggestions =
        service
            .suggest(request.getLocale(), request.getCount().getValue(), request.getExclude())
            .stream()
            .map(IdentitySuggestionControllerDelegate::toDto)
            .toList();
    // Suggestions are a snapshot of a shared namespace, never a reservation: do not let them cache.
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(suggestions);
  }

  private static GuestIdentitySuggestion toDto(
      de.caritas.cob.userservice.api.service.identity.GuestIdentitySuggestion suggestion) {
    var dto = new GuestIdentitySuggestion();
    dto.setUsername(suggestion.username());
    dto.setDisplayName(suggestion.displayName());
    dto.setAvatarKey(suggestion.avatarKey());
    return dto;
  }
}
