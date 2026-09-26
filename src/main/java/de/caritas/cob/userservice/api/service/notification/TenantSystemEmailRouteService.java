package de.caritas.cob.userservice.api.service.notification;

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

  public static class ConfigurationException extends IllegalStateException {
    public ConfigurationException(String message) {
      super(message);
    }
  }

  private final @NonNull TenantSystemEmailClient tenantClient;

  public Optional<Route> resolve(Long tenantId) {
    if (tenantId == null || tenantId <= 0) return Optional.empty();
    Map<String, Object> tenant = tenantClient.readTenant(tenantId);
    Map<?, ?> settings = map(tenant.get("settings"));
    if (settings == null) {
      throw new ConfigurationException("Tenant system-mail settings are missing");
    }
    if (!Boolean.TRUE.equals(settings.get("featureSystemNotificationEmailsEnabled"))) {
      return Optional.empty();
    }

    Map<?, ?> smtp = map(settings.get("smtp"));
    String color = smtp == null ? null : string(smtp.get("emailThemeColor"));
    if ("PLATFORM".equals(settings.get("smtpMode"))) {
      return Optional.of(new Route(Mode.PLATFORM, color));
    }
    if (!"OWN".equals(settings.get("smtpMode"))) {
      throw new ConfigurationException("Tenant smtpMode must be PLATFORM or OWN");
    }
    if (smtp == null) {
      throw new ConfigurationException("OWN tenant SMTP settings are missing");
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
      throw new ConfigurationException("OWN tenant SMTP configuration is incomplete");
    }
    return Optional.of(new Route(Mode.OWN, color));
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
