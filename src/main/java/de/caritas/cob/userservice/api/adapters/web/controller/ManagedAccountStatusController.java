package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService;
import de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService.AccountStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/useradmin/accounts", "/service/useradmin/accounts"})
public class ManagedAccountStatusController {
  private final ManagedAccountStatusService service;

  public record UpdateStatus(@NotNull Boolean active) {}

  @GetMapping("/{accountId}/status")
  public ResponseEntity<AccountStatus> read(@PathVariable String accountId) {
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(service.read(accountId));
  }

  @PutMapping("/{accountId}/status")
  public ResponseEntity<AccountStatus> update(
      @PathVariable String accountId, @Valid @RequestBody UpdateStatus input) {
    try {
      return ResponseEntity.ok()
          .cacheControl(org.springframework.http.CacheControl.noStore())
          .body(service.update(accountId, input.active()));
    } catch (ManagedAccountStatusService.ActivationRefused refused) {
      return ResponseEntity.status(409)
          .cacheControl(org.springframework.http.CacheControl.noStore())
          .body(refused.status());
    }
  }
}
