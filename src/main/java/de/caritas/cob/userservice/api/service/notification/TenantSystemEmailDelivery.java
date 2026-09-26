package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Sends one rendered system mail through its tenant's explicit transport. */
@Service
@RequiredArgsConstructor
public class TenantSystemEmailDelivery {
  public enum Purpose {
    EMAIL_ADDRESS_CHANGED,
    SUPERVISOR_ADDED,
    SUPERVISOR_REMOVED,
    NEW_ENQUIRY,
    DIRECT_ENQUIRY,
    ENQUIRY_ASSIGNED,
    DAILY_ENQUIRY_DIGEST,
    NEW_MESSAGE
  }

  private final @NonNull TenantSystemEmailClient tenantClient;
  private final @NonNull PlatformSmtpSettingsProvider platformSettings;
  private final @NonNull OrisoEmailDispatcher platformDispatcher;

  public void send(
      long tenantId,
      TenantSystemEmailRouteService.Route route,
      Purpose purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email) {
    sendConfirmed(tenantId, route, purpose, recipient, email);
  }

  /** Returns false only when the platform SMTP dispatcher rejected the send. */
  public boolean sendConfirmed(
      long tenantId,
      TenantSystemEmailRouteService.Route route,
      Purpose purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email) {
    if (route.mode() == TenantSystemEmailRouteService.Mode.OWN) {
      tenantClient.deliver(tenantId, purpose.name(), recipient, email);
      return true;
    } else {
      return platformDispatcher.send(platformSettings.requireConfigured(), recipient, email);
    }
  }
}
