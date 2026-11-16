package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPlatformTextService;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortTranslations;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The platform admin's Erstantwort texts (Admin: Settings → Begrüßung). They replace the built-in
 * Caritas default for every Träger that has not written its own text. Platform admins only: the
 * security config gates the paths ({@code isPlatformAdmin}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/useradmin/erstantwort/platform", "/service/useradmin/erstantwort/platform"})
public class ErstantwortPlatformTextsController {

  private final @NonNull ErstantwortPlatformTextService service;

  /**
   * @param texts Baustein → language → HTML (plus {@code <lang>__meta}); the platform admin's own
   * @param builtIn Baustein → language → plain text: the Caritas default each language falls back
   *     to
   */
  public record PlatformTexts(
      Map<String, Map<String, String>> texts, Map<String, Map<String, String>> builtIn) {}

  public record UpdatePlatformTextsRequest(Map<String, Map<String, String>> texts) {}

  @GetMapping
  public ResponseEntity<PlatformTexts> read() {
    return ResponseEntity.ok(
        new PlatformTexts(service.readPlatformTexts(), ErstantwortTranslations.builtInDefaults()));
  }

  @PutMapping
  public ResponseEntity<PlatformTexts> update(
      @RequestBody UpdatePlatformTextsRequest request, @AuthenticationPrincipal Jwt jwt) {
    if (request == null || request.texts() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "texts missing");
    }
    try {
      service.replacePlatformTexts(request.texts(), actor(jwt));
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
    return read();
  }

  private static String actor(Jwt jwt) {
    if (jwt == null) return null;
    var username = jwt.getClaimAsString("preferred_username");
    return username != null ? username : jwt.getSubject();
  }
}
