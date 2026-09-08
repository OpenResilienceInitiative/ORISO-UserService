package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.notification.DpaSignedNoticeService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated fire-and-forget hint from TenantService that a DPA signature landed for a tenant
 * (ORISO-UserService#1005). Deliberately carries NO data and returns NO information: the answer is
 * always 202, whether or not the tenant exists, a forward happened or a notice went out. All facts
 * are read back from TenantService through the authenticated technical-user client, and the
 * exactly-once ledger absorbs repeated or spoofed hints — see {@link DpaSignedNoticeService}.
 * Mapped with and without the {@code /service} prefix like the other public endpoints.
 */
@RestController
@RequiredArgsConstructor
public class DpaSignedNoticeController {

  private final @NonNull DpaSignedNoticeService dpaSignedNoticeService;

  @PostMapping({
    "/users/tenants/{tenantId}/dpa-signed-notices",
    "/service/users/tenants/{tenantId}/dpa-signed-notices"
  })
  public ResponseEntity<Void> onSignatureHint(@PathVariable Long tenantId) {
    dpaSignedNoticeService.onSignatureHint(tenantId);
    return ResponseEntity.accepted().build();
  }
}
