package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService.InvalidSettingsException;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService.StaleRevisionException;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsValues;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin → Global settings: lifetimes, password field and message retention for temporary accounts
 * (ORISO-Admin#1241, ADR-029 §3–§5). Platform admin only. A change applies to people who join
 * afterwards; existing accounts keep their copied values.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/users/admin/temporary-account-settings",
  "/service/users/admin/temporary-account-settings"
})
public class TemporaryAccountSettingsController {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull TemporaryAccountSettingsService settingsService;

  @GetMapping
  public ResponseEntity<TemporaryAccountSettingsValues> get() {
    requirePlatformAdmin();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settingsService.current());
  }

  @PutMapping
  public ResponseEntity<TemporaryAccountSettingsValues> update(
      @RequestBody(required = false) TemporaryAccountSettingsValues values) {
    requirePlatformAdmin();
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(settingsService.update(values, authenticatedUser.getUserId()));
    } catch (InvalidSettingsException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    } catch (StaleRevisionException | org.springframework.dao.DataIntegrityViolationException e) {
      // A concurrent first save collides on the singleton key: same answer as a stale revision.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Reload the current settings before saving");
    }
  }

  private void requirePlatformAdmin() {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator required");
    }
  }
}
