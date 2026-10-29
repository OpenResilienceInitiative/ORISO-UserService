package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/matrix/calls", "/service/matrix/calls"})
@RequiredArgsConstructor
public class MatrixCallStateController {
  private final MatrixCallStateService states;
  private final MatrixCallGuestService guests;
  private final AuthenticatedUser authenticatedUser;

  @GetMapping("/state")
  public ResponseEntity<?> getState(
      @RequestParam String sourceRoomId, @RequestParam String callId) {
    try {
      return states
          .read(authenticatedUser, sourceRoomId, callId)
          .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
          .orElseGet(() -> ResponseEntity.notFound().build());
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      return unavailable();
    }
  }

  /**
   * Lets a person into one running call without the source conversation (US#1477). Only a
   * counsellor in the conversation may admit; the guest is invited into the call room alone.
   */
  @PostMapping("/guests")
  public ResponseEntity<?> admitGuest(@RequestBody GuestAdmissionRequest request) {
    try {
      var admission =
          guests.admit(
              authenticatedUser, request.sourceRoomId(), request.callId(), request.guestUserId());
      return switch (admission.outcome()) {
        case ADMITTED ->
            ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                    new GuestAdmissionResponse(
                        admission.callRoomId(),
                        admission.guestMatrixUserId(),
                        admission.admittedAt()));
        case ALREADY_PARTICIPANT ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("reason", "already_conversation_member"));
        case MATRIX_UNAVAILABLE -> unavailable();
        case NOT_FOUND -> ResponseEntity.notFound().build();
      };
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      return unavailable();
    }
  }

  private static ResponseEntity<?> unavailable() {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .cacheControl(CacheControl.noStore())
        .body(java.util.Map.of("reason", "call_state_lookup_unavailable"));
  }

  public record GuestAdmissionRequest(String sourceRoomId, String callId, String guestUserId) {}

  public record GuestAdmissionResponse(
      String callRoomId, String guestMatrixUserId, Long admittedAt) {}
}
