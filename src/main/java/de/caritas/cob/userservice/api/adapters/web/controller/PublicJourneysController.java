package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.journey.PublicJourneyService;
import de.caritas.cob.userservice.api.service.journey.PublicJourneyService.PublicJourney;
import java.util.NoSuchElementException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The journey an advice seeker runs, read without a login (ADR-036 part E, ORISO-Frontend#2333).
 * 404 means no journey is stored for this way in: the registration keeps its form of today.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/users/journeys", "/service/users/journeys"})
public class PublicJourneysController {

  private final @NonNull PublicJourneyService service;

  /**
   * The journey whose registration runs for a topic; with a centre, as its Träger and it adapted.
   */
  @GetMapping("/registration")
  public ResponseEntity<PublicJourney> forRegistration(
      @RequestParam long topicId, @RequestParam(required = false) Long agencyId) {
    return answer(service.forRegistration(topicId, agencyId).orElse(null));
  }

  /** The journey behind {@code /l/<slug>}, with the link's topics and preselected centre. */
  @GetMapping("/links/{slug}")
  public ResponseEntity<PublicJourney> forLink(
      @PathVariable String slug, @RequestParam(required = false) Long agencyId) {
    return answer(service.forLink(slug, agencyId).orElse(null));
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Void> unknown() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }

  private static ResponseEntity<PublicJourney> answer(PublicJourney journey) {
    if (journey == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    // Admins see their changes on the next registration, not after a cache expires.
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(journey);
  }
}
