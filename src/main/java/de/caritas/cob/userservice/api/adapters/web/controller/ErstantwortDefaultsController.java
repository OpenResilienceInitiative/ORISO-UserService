package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortTranslations;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only: the platform's default Erstantwort texts (ADR-018 §6, ORISO-Admin#601), so the Träger
 * editor can show what is sent while a field stays empty. Plain text from the same catalogue the
 * send path uses; nothing tenant-specific, nothing writable.
 */
@RestController
public class ErstantwortDefaultsController {

  private static final String ADMIN_AUTH =
      "hasAnyAuthority('AUTHORIZATION_TENANT_ADMIN', 'AUTHORIZATION_SINGLE_TENANT_ADMIN',"
          + " 'AUTHORIZATION_RESTRICTED_AGENCY_ADMIN', 'AUTHORIZATION_USER_ADMIN')";

  @PreAuthorize(ADMIN_AUTH)
  @GetMapping({"/useradmin/erstantwort/defaults", "/service/useradmin/erstantwort/defaults"})
  public ResponseEntity<Map<String, Object>> defaults(
      @RequestParam(required = false) String locale,
      @RequestParam(defaultValue = "false") boolean informal) {
    try {
      return ResponseEntity.ok(ErstantwortTranslations.platformDefaults(locale, informal));
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported locale");
    }
  }
}
