package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.JourneyRecord.AGENCY;
import static de.caritas.cob.userservice.api.model.JourneyRecord.TENANT;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.service.journey.JourneyConflictException;
import de.caritas.cob.userservice.api.service.journey.JourneyService;
import de.caritas.cob.userservice.api.service.journey.JourneyService.Stored;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journeys per conversation type (ADR-036 part E, ORISO-UserService#1689).
 *
 * <ul>
 *   <li>Platform journeys: every admin reads them (lower levels see what they inherit); only the
 *       platform admin writes them — the security config gates the write paths.
 *   <li>Layers of a Träger or Beratungsstelle: narrowed to the caller's {@link AdminScope}. A
 *       centre admin also reads the layer of its own Träger, because that is what it builds on.
 * </ul>
 *
 * Every write carries the version the writer read ({@code 0} for something new); another version is
 * answered with 409 and the current version.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/useradmin/journeys", "/service/useradmin/journeys"})
public class JourneysController {

  private final @NonNull JourneyService service;
  private final @NonNull AdminScope adminScope;

  public record JourneyEntry(String journeyId, long version, Map<String, Object> journey) {}

  public record LayerEntry(String journeyId, long version, Map<String, Object> phases) {}

  public record SaveJourneyRequest(Long version, Map<String, Object> journey) {}

  public record SaveLayerRequest(Long version, Map<String, Object> phases) {}

  public record Conflict(String error, long currentVersion) {}

  @GetMapping("/platform")
  public List<JourneyEntry> platformJourneys() {
    return service.platformJourneys().stream().map(JourneysController::journeyEntry).toList();
  }

  @PutMapping("/platform/{journeyId}")
  public JourneyEntry savePlatformJourney(
      @PathVariable String journeyId,
      @RequestBody SaveJourneyRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    requireBody(request != null && request.version() != null && request.journey() != null);
    return journeyEntry(
        service.savePlatformJourney(journeyId, request.version(), request.journey(), actor(jwt)));
  }

  @DeleteMapping("/platform/{journeyId}")
  public ResponseEntity<Void> deletePlatformJourney(
      @PathVariable String journeyId, @RequestParam long version) {
    service.deletePlatformJourney(journeyId, version);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/tenants/{tenantId}/layers")
  public List<LayerEntry> tenantLayers(@PathVariable Long tenantId) {
    assertMayReadTenant(tenantId);
    return service.layers(TENANT, tenantId).stream().map(JourneysController::layerEntry).toList();
  }

  @PutMapping("/tenants/{tenantId}/layers/{journeyId}")
  public ResponseEntity<LayerEntry> saveTenantLayer(
      @PathVariable Long tenantId,
      @PathVariable String journeyId,
      @RequestBody SaveLayerRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    adminScope.assertMay(Target.tenant(tenantId));
    return saveLayer(TENANT, tenantId, journeyId, request, jwt);
  }

  @GetMapping("/agencies/{agencyId}/layers")
  public List<LayerEntry> agencyLayers(@PathVariable Long agencyId) {
    adminScope.assertMay(Target.agencies(List.of(agencyId)));
    return service.layers(AGENCY, agencyId).stream().map(JourneysController::layerEntry).toList();
  }

  @PutMapping("/agencies/{agencyId}/layers/{journeyId}")
  public ResponseEntity<LayerEntry> saveAgencyLayer(
      @PathVariable Long agencyId,
      @PathVariable String journeyId,
      @RequestBody SaveLayerRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    adminScope.assertMay(Target.agencies(List.of(agencyId)));
    return saveLayer(AGENCY, agencyId, journeyId, request, jwt);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException invalid) {
    return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(invalid.getMessage())));
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Map<String, String>> missing(NoSuchElementException missing) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", String.valueOf(missing.getMessage())));
  }

  @ExceptionHandler(JourneyConflictException.class)
  public ResponseEntity<Conflict> conflict(JourneyConflictException conflict) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new Conflict("JOURNEY_CHANGED", conflict.getCurrentVersion()));
  }

  /** Two writers passed the version check at the same moment: the second one lost. */
  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    DataIntegrityViolationException.class
  })
  public ResponseEntity<Conflict> race(RuntimeException race) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new Conflict("JOURNEY_CHANGED", -1));
  }

  private ResponseEntity<LayerEntry> saveLayer(
      String level, Long ownerId, String journeyId, SaveLayerRequest request, Jwt jwt) {
    requireBody(request != null && request.version() != null && request.phases() != null);
    return service
        .saveLayer(level, ownerId, journeyId, request.version(), request.phases(), actor(jwt))
        .map(stored -> ResponseEntity.ok(layerEntry(stored)))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /** A Träger's layer: the Träger itself, or a centre admin of that Träger (who builds on it). */
  private void assertMayReadTenant(Long tenantId) {
    if (adminScope.current() instanceof AdminScope.Agencies agencies
        && Objects.equals(agencies.tenantId(), tenantId)) {
      return;
    }
    adminScope.assertMay(Target.tenant(tenantId));
  }

  private static void requireBody(boolean present) {
    if (!present) {
      throw new IllegalArgumentException("version and content are required");
    }
  }

  private static JourneyEntry journeyEntry(Stored stored) {
    return new JourneyEntry(stored.journeyId(), stored.version(), stored.body());
  }

  @SuppressWarnings("unchecked")
  private static LayerEntry layerEntry(Stored stored) {
    return new LayerEntry(
        stored.journeyId(),
        stored.version(),
        (Map<String, Object>) stored.body().getOrDefault("phases", Map.of()));
  }

  private static String actor(Jwt jwt) {
    if (jwt == null) return null;
    var username = jwt.getClaimAsString("preferred_username");
    return username != null ? username : jwt.getSubject();
  }
}
