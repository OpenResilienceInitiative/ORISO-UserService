package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantLiveAvailabilityRequestDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the consultant app drive live-chat availability directly from Live Chat enable/disable and
 * logout events, so the anonymous availability count updates immediately instead of waiting for the
 * heartbeat to expire.
 */
@RestController
@RequiredArgsConstructor
public class ConsultantLiveAvailabilityController {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ConsultantActivityRegistry consultantActivityRegistry;

  @PutMapping({
    "/conversations/consultants/availability",
    "/service/conversations/consultants/availability"
  })
  public ResponseEntity<Void> setLiveChatAvailability(
      @RequestBody ConsultantLiveAvailabilityRequestDTO request) {

    if (authenticatedUser.isConsultant() && authenticatedUser.getUserId() != null) {
      if (Boolean.TRUE.equals(request.getAvailable())) {
        consultantActivityRegistry.markAvailable(authenticatedUser.getUserId());
      } else {
        consultantActivityRegistry.markUnavailable(authenticatedUser.getUserId());
      }
    }

    return ResponseEntity.noContent().build();
  }
}
