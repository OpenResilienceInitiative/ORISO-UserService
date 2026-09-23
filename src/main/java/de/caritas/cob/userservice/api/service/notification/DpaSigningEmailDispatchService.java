package de.caritas.cob.userservice.api.service.notification;

import java.time.LocalDateTime;

public interface DpaSigningEmailDispatchService {
  void send(String recipientEmail, String tenantName, String signLink, LocalDateTime expiresAt);

  DpaSigningEmailPreview preview(
      String recipientEmail, String tenantName, String signLink, LocalDateTime expiresAt);
}
