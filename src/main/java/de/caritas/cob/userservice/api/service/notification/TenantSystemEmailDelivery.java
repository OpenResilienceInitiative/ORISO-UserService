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
    CONTACT_SHEET,
    SELF_HELP_APPOINTMENT_CONFIRMED,
    SELF_HELP_APPOINTMENT_RESCHEDULED,
    SELF_HELP_APPOINTMENT_CANCELLED,
    SELF_HELP_APPOINTMENT_REMINDER
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
    return sendConfirmed(tenantId, route, purpose, recipient, email, null);
  }

  /** The correlation ID is persisted by an outbox and reused for transport reconciliation. */
  public boolean sendConfirmed(
      long tenantId,
      TenantSystemEmailRouteService.Route route,
      Purpose purpose,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email,
      String correlationId) {
    if (route.mode() == TenantSystemEmailRouteService.Mode.OWN) {
      if (correlationId == null) {
        tenantClient.deliver(tenantId, purpose.name(), recipient, email);
      } else {
        tenantClient.deliver(tenantId, purpose.name(), recipient, email, correlationId);
      }
      return true;
    } else {
      var settings = platformSettings.requireConfigured();
      return correlationId == null
          ? platformDispatcher.send(settings, recipient, email)
          : platformDispatcher.send(settings, recipient, email, correlationId);
    }
  }
}
