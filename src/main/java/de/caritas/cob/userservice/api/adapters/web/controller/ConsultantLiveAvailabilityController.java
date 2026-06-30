package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantLiveAvailabilityRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantLiveAvailabilityResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the consultant app drive live-chat availability directly from Live Chat enable/disable and
 * logout events, so the anonymous availability count updates immediately instead of waiting for the
 * heartbeat to expire.
 *
 * <p>Also exposes a self-availability READ (ADR-007) so the consultant app can drive its "live"
 * indicator from the authoritative {@link ConsultantActivityRegistry} instead of mirroring a
 * client-side flag (which is the root cause of the "stuck live" indicator).
 */
@RestController
@RequiredArgsConstructor
public class ConsultantLiveAvailabilityController {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ConsultantActivityRegistry consultantActivityRegistry;

  /**
   * The live-chat availability window in milliseconds. Kept in sync with {@code
   * TopicConsultantRoutingService} (same property + default) so the READ and the routing read
   * agree.
   */
  @Value("${consultant.availability.activeWindowMs:120000}")
  private long activeWindowMs;

  @PutMapping("/conversations/consultants/availability")
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

  /**
   * Returns whether the calling consultant is currently within the live-chat availability window,
   * read from {@link ConsultantActivityRegistry}. Non-consultants always read {@code false}.
   */
  @GetMapping("/conversations/consultants/availability")
  public ResponseEntity<ConsultantLiveAvailabilityResponseDTO> getLiveChatAvailability() {
    boolean available = false;
    if (authenticatedUser.isConsultant() && authenticatedUser.getUserId() != null) {
      String userId = authenticatedUser.getUserId();
      available =
          consultantActivityRegistry.filterActive(List.of(userId), activeWindowMs).contains(userId);
    }
    return ResponseEntity.ok(new ConsultantLiveAvailabilityResponseDTO(available));
  }
}
