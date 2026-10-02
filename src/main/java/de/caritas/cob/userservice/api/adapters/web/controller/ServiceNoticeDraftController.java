package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService.DraftInput;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService.DraftView;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeDraftService.Preview;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Platform-only planned-notice draft and no-send preview. No confirmation or send route exists. */
@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/users/admin/service-notices/drafts",
  "/service/users/admin/service-notices/drafts"
})
public class ServiceNoticeDraftController {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ServiceNoticeDraftService drafts;

  @PutMapping("/{campaignKey}")
  public ResponseEntity<DraftView> save(
      @PathVariable String campaignKey, @Valid @RequestBody DraftInput input) {
    requirePlatformAdmin();
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(drafts.save(campaignKey, input, authenticatedUser.getUserId()));
    } catch (ServiceNoticeDraftService.DraftConflict conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
  }

  @GetMapping("/{campaignKey}")
  public ResponseEntity<DraftView> get(@PathVariable String campaignKey) {
    requirePlatformAdmin();
    try {
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(drafts.get(campaignKey));
    } catch (NoSuchElementException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service notice draft not found");
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
  }

  @GetMapping("/{campaignKey}/preview")
  public ResponseEntity<Preview> preview(
      @PathVariable String campaignKey, @RequestParam String variant) {
    requirePlatformAdmin();
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(drafts.preview(campaignKey, variant));
    } catch (NoSuchElementException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service notice draft not found");
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
  }

  private void requirePlatformAdmin() {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator required");
    }
  }
}
