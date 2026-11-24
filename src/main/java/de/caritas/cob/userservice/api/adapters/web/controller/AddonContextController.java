package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AddonContextDTO;
import de.caritas.cob.userservice.api.service.addon.AddonContextService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Add-on user context (contract oriso-addon/v1), called by the AddonService only. */
@RestController
@RequiredArgsConstructor
public class AddonContextController {

  private final @NonNull AddonContextService addonContextService;

  @GetMapping("/users/addon-context")
  public ResponseEntity<AddonContextDTO> getAddonContext(
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) Long tenantId) {
    return ResponseEntity.ok(addonContextService.contextOf(userId, tenantId));
  }
}
