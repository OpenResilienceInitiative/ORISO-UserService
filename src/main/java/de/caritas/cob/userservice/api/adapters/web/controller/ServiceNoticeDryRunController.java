package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.DryRun;
import java.util.NoSuchElementException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Platform-only count of who a planned notice would reach. It never sends or records anything. */
@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/users/admin/service-notices/drafts",
  "/service/users/admin/service-notices/drafts"
})
public class ServiceNoticeDryRunController {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ServiceNoticeAudience audience;

  @GetMapping("/{campaignKey}/dry-run")
  public ResponseEntity<DryRun> dryRun(@PathVariable String campaignKey) {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator required");
    }
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(audience.dryRun(campaignKey));
    } catch (NoSuchElementException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service notice draft not found");
    }
  }
}
