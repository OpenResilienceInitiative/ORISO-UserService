package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.service.notification.NotificationEmailDiagnostics.*;

import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Selects one explicit mail transport from a fresh, redacted tenant read. */
@Service
@RequiredArgsConstructor
public class TenantSystemEmailRouteService {
  public enum Mode {
    PLATFORM,
    OWN
  }

  public record Route(Mode mode, String emailThemeColor) {}

  public static class ConfigurationException extends Failure {
    public ConfigurationException(String message) {
      super(Stage.TENANT_SMTP, Reason.CONFIGURATION_INVALID, message);
    }

    public ConfigurationException(Stage stage, Reason reason, String message) {
      super(stage, reason, message);
    }
  }

  private final @NonNull TenantSystemEmailClient tenantClient;

  public Optional<Route> resolve(Long tenantId) {
    if (tenantId == null || tenantId <= 0) return Optional.empty();
    TenantSystemEmailClient.MailContext context = tenantClient.readMailContext(tenantId);
    Boolean notificationPolicy = context.systemNotificationEmailsEnabled();
    if (Boolean.FALSE.equals(notificationPolicy)) {
      return Optional.empty();
    }
    if (!Boolean.TRUE.equals(notificationPolicy)) {
      throw new ConfigurationException(
          Stage.TENANT_POLICY,
          Reason.NOTIFICATION_POLICY_INVALID,
          "Tenant notification policy is missing or invalid");
    }
    return Optional.of(route(context));
  }

  /**
   * Transport for mails that are always sent (invites, DPA mails): the notification switch only
   * mutes notifications, so it must not decide whether these mails go out or from where.
   */
  public Route resolveTransport(Long tenantId) {
    if (tenantId == null || tenantId <= 0) return new Route(Mode.PLATFORM, null);
    TenantSystemEmailClient.MailContext context = tenantClient.readMailContext(tenantId);
    // Keep the legacy platform route only when no tenant transport was configured. Existing
    // transport data needs an explicit mode; guessing PLATFORM could send from the wrong server.
    if (context.smtpMode() == null && !context.smtpConfigurationPresent()) {
      return new Route(Mode.PLATFORM, null);
    }
    return route(context);
  }

  private static Route route(TenantSystemEmailClient.MailContext context) {
    if ("PLATFORM".equals(context.smtpMode())) {
      return new Route(Mode.PLATFORM, context.emailThemeColor());
    }
    if (!"OWN".equals(context.smtpMode())) {
      throw new ConfigurationException(
          Stage.TENANT_SMTP, Reason.SMTP_MODE_INVALID, "Tenant smtpMode must be PLATFORM or OWN");
    }
    if (!context.ownSmtpConfigured()) {
      throw new ConfigurationException(
          Stage.TENANT_SMTP,
          Reason.OWN_SMTP_INCOMPLETE,
          "OWN tenant SMTP configuration is incomplete");
    }
    return new Route(Mode.OWN, context.emailThemeColor());
  }
}
