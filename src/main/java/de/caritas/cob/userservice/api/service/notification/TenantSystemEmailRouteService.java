package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.service.notification.NotificationEmailDiagnostics.*;

import java.util.Map;
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
    Map<?, ?> settings = readSettings(tenantId);
    Object notificationPolicy = settings.get("featureSystemNotificationEmailsEnabled");
    if (Boolean.FALSE.equals(notificationPolicy)) {
      return Optional.empty();
    }
    if (!Boolean.TRUE.equals(notificationPolicy)) {
      throw new ConfigurationException(
          Stage.TENANT_POLICY,
          Reason.NOTIFICATION_POLICY_INVALID,
          "Tenant notification policy is missing or invalid");
    }
    return Optional.of(route(settings));
  }

  /**
   * Transport for mails that are always sent (invites, DPA mails): the notification switch only
   * mutes notifications, so it must not decide whether these mails go out or from where.
   */
  public Route resolveTransport(Long tenantId) {
    if (tenantId == null || tenantId <= 0) return new Route(Mode.PLATFORM, null);
    Map<?, ?> settings = map(tenantClient.readTenant(tenantId).get("settings"));
    // Keep the legacy platform route only when no tenant transport was configured. Existing
    // transport data needs an explicit mode; guessing PLATFORM could send from the wrong server.
    if (settings == null
        || (settings.get("smtpMode") == null && !hasLegacySmtpConfiguration(settings))) {
      return new Route(Mode.PLATFORM, null);
    }
    return route(settings);
  }

  private static boolean hasLegacySmtpConfiguration(Map<?, ?> settings) {
    Map<?, ?> smtp = map(settings.get("smtp"));
    if (smtp == null) return false;
    for (String key :
        new String[] {"enabled", "host", "port", "secure", "username", "from", "passwordSet"}) {
      Object value = smtp.get(key);
      if (value != null
          && !Boolean.FALSE.equals(value)
          && !(value instanceof String text && text.isBlank())) {
        return true;
      }
    }
    return false;
  }

  private Map<?, ?> readSettings(long tenantId) {
    Map<?, ?> settings = map(tenantClient.readTenant(tenantId).get("settings"));
    if (settings == null) {
      throw new ConfigurationException(
          Stage.TENANT_POLICY,
          Reason.TENANT_SETTINGS_MISSING,
          "Tenant system-mail settings are missing");
    }
    return settings;
  }

  private static Route route(Map<?, ?> settings) {
    Map<?, ?> smtp = map(settings.get("smtp"));
    String color = smtp == null ? null : string(smtp.get("emailThemeColor"));
    if ("PLATFORM".equals(settings.get("smtpMode"))) {
      return new Route(Mode.PLATFORM, color);
    }
    if (!"OWN".equals(settings.get("smtpMode"))) {
      throw new ConfigurationException(
          Stage.TENANT_SMTP, Reason.SMTP_MODE_INVALID, "Tenant smtpMode must be PLATFORM or OWN");
    }
    if (smtp == null) {
      throw new ConfigurationException(
          Stage.TENANT_SMTP, Reason.OWN_SMTP_MISSING, "OWN tenant SMTP settings are missing");
    }
    Object port = smtp.get("port");
    if (!Boolean.TRUE.equals(smtp.get("enabled"))
        || blank(smtp.get("host"))
        || !(port instanceof Number number)
        || number.intValue() < 1
        || number.intValue() > 65535
        || !(smtp.get("secure") instanceof Boolean)
        || blank(smtp.get("username"))
        || blank(smtp.get("from"))
        || !Boolean.TRUE.equals(smtp.get("passwordSet"))) {
      throw new ConfigurationException(
          Stage.TENANT_SMTP,
          Reason.OWN_SMTP_INCOMPLETE,
          "OWN tenant SMTP configuration is incomplete");
    }
    return new Route(Mode.OWN, color);
  }

  private static Map<?, ?> map(Object value) {
    return value instanceof Map<?, ?> result ? result : null;
  }

  private static String string(Object value) {
    return value instanceof String text ? text : null;
  }

  private static boolean blank(Object value) {
    return !(value instanceof String text) || text.isBlank();
  }
}
