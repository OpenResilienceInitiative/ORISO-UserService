package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AddonNotificationCreatedDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AddonNotificationRequestDTO;
import de.caritas.cob.userservice.api.service.notification.AddonNotificationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Add-on host notifications (contract oriso-addon/v1), called by the AddonService only. */
@RestController
@RequiredArgsConstructor
public class AddonNotificationController {

  private final @NonNull AddonNotificationService addonNotificationService;

  @PostMapping("/users/event-notifications/addon")
  public ResponseEntity<AddonNotificationCreatedDTO> createAddonNotification(
      @RequestBody AddonNotificationRequestDTO request) {
    var id = addonNotificationService.create(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(new AddonNotificationCreatedDTO(id));
  }
}
