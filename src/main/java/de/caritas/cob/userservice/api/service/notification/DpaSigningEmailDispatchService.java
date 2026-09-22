package de.caritas.cob.userservice.api.service.notification;

import java.time.LocalDateTime;

/** Sends and previews the DPA signing mail; preview and send render the same document. */
public interface DpaSigningEmailDispatchService {

  /**
   * @param tenantId tenant whose branding the mail carries (may be only reserved, not created)
   * @param expiresAt zoneless UTC, as TenantService issues it
   */
  void send(
      Long tenantId,
      String recipientEmail,
      String tenantName,
      String signLink,
      LocalDateTime expiresAt);

  DpaSigningEmailPreview preview(
      Long tenantId,
      String recipientEmail,
      String tenantName,
      String signLink,
      LocalDateTime expiresAt);
}
