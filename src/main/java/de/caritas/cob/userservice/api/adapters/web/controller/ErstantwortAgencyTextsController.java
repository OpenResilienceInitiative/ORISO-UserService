package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPlatformTextService;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A Beratungsstelle's own Erstantwort texts (Admin: Beratungsstelle → Einführung Ratsuchende),
 * above the Träger's (ADR-018 §6). Reach comes from {@link AdminScope}: platform admins everywhere,
 * Träger admins within their centres, agency admins only their own centres.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/useradmin/erstantwort/agencies/{agencyId}",
  "/service/useradmin/erstantwort/agencies/{agencyId}"
})
public class ErstantwortAgencyTextsController {

  private final @NonNull ErstantwortPlatformTextService service;
  private final @NonNull AdminScope adminScope;

  public record AgencyTexts(Map<String, Map<String, String>> texts) {}

  @GetMapping
  public ResponseEntity<AgencyTexts> read(@PathVariable Long agencyId) {
    adminScope.assertMay(Target.agencies(List.of(agencyId)));
    return ResponseEntity.ok(new AgencyTexts(service.readAgencyTexts(agencyId)));
  }

  @PutMapping
  public ResponseEntity<AgencyTexts> update(
      @PathVariable Long agencyId,
      @RequestBody AgencyTexts request,
      @AuthenticationPrincipal Jwt jwt) {
    adminScope.assertMay(Target.agencies(List.of(agencyId)));
    if (request == null || request.texts() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "texts missing");
    }
    try {
      service.replaceAgencyTexts(agencyId, request.texts(), jwt == null ? null : jwt.getSubject());
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
    return ResponseEntity.ok(new AgencyTexts(service.readAgencyTexts(agencyId)));
  }
}
