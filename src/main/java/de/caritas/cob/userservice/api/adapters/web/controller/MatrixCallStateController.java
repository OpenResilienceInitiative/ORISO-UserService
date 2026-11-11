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
  private final de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService
      observations;
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

  /** Creator of an active encrypted internal-group call registers it for observation only. */
  @PostMapping("/observations")
  public ResponseEntity<?> register(@RequestBody ObservationRequest request) {
    try {
      return observationResponse(
          observations.register(
              authenticatedUser,
              request.sourceRoomId(),
              request.callId(),
              request.callRoomId(),
              Boolean.TRUE.equals(request.video())));
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      return unavailable();
    } catch (org.springframework.dao.PessimisticLockingFailureException concurrent) {
      // Lock timeout or deadlock between two first registrations on the same call.
      return registrationConflict();
    } catch (org.springframework.dao.DataIntegrityViolationException violation) {
      // Only the two unique keys mean "two first registrations raced"; any other integrity
      // failure is a server defect and must stay visible instead of looking retryable.
      if (!violatesObservationUniqueKey(violation)) throw violation;
      return registrationConflict();
    }
  }

  private static ResponseEntity<?> registrationConflict() {
    // A retry takes the renew path.
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .cacheControl(CacheControl.noStore())
        .body(java.util.Map.of("reason", "call_observation_conflict"));
  }

  private static boolean violatesObservationUniqueKey(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("uk_call_observation_source_call")
            || lower.contains("uk_call_observation_media_room")) {
          return true;
        }
      }
      if (cause.getCause() == cause) break;
    }
    return false;
  }

  @PostMapping("/observations/end")
  public ResponseEntity<?> end(@RequestBody ObservationEndRequest request) {
    try {
      return observationResponse(
          observations.end(authenticatedUser, request.sourceRoomId(), request.callId()));
    } catch (MatrixSynapseService.CallLookupUnavailableException unavailable) {
      return unavailable();
    } catch (org.springframework.dao.PessimisticLockingFailureException concurrent) {
      // Racing the creator's renew or end; the client may simply repeat the call.
      return registrationConflict();
    }
  }

  private static ResponseEntity<?> observationResponse(
      de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome outcome) {
    return outcome
            == de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService.Outcome.OK
        ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
        : ResponseEntity.notFound().build();
  }

  public record ObservationRequest(
      String sourceRoomId, String callId, String callRoomId, Boolean video) {}

  public record ObservationEndRequest(String sourceRoomId, String callId) {}

  private static ResponseEntity<?> unavailable() {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .cacheControl(CacheControl.noStore())
        .body(java.util.Map.of("reason", "call_state_lookup_unavailable"));
  }

  public record GuestAdmissionRequest(String sourceRoomId, String callId, String guestUserId) {}

  public record GuestAdmissionResponse(
      String callRoomId, String guestMatrixUserId, Long admittedAt) {}
}
