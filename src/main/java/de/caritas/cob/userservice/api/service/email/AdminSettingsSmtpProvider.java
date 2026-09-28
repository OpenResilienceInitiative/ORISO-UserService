package de.caritas.cob.userservice.api.service.email;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsSmtpCredentialsDTO;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Resolves platform SMTP settings from the Admin Settings owned by ConsultingTypeService. */
@Component
public class AdminSettingsSmtpProvider {

  private final RestTemplate restTemplate;
  private final ApplicationSettingsService applicationSettingsService;
  private final String consultingTypeServiceApiUrl;

  public AdminSettingsSmtpProvider(
      RestTemplate restTemplate,
      ApplicationSettingsService applicationSettingsService,
      @Value("${consulting.type.service.api.url:}") String consultingTypeServiceApiUrl) {
    this.restTemplate = restTemplate;
    this.applicationSettingsService = applicationSettingsService;
    this.consultingTypeServiceApiUrl = consultingTypeServiceApiUrl;
  }

  /**
   * Returns a complete SMTP configuration or fails closed when Admin Settings do not provide one.
   * Credentials are deliberately read through the technical service identity, never from Helm
   * environment variables and never from the public settings payload.
   */
  @SuppressWarnings("unchecked")
  public Settings requireConfigured() {
    if (isBlank(consultingTypeServiceApiUrl)) {
      throw incomplete("CONSULTING_TYPE_SERVICE_API_URL is not configured");
    }

    Map<String, Object> response;
    try {
      response =
          restTemplate.getForObject(
              normalizeBaseUrl(consultingTypeServiceApiUrl) + "/settings", Map.class);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Platform SMTP settings could not be loaded from Admin Settings", exception);
    }

    if (response == null || response.isEmpty()) {
      throw incomplete("Admin Settings returned no SMTP configuration");
    }

    if (!asBoolean(response.get("globalFeatureSystemNotificationEmailsEnabled"))
        || !asBoolean(response.get("globalSmtpEnabled"))) {
      throw incomplete("system notification email or SMTP is disabled in Admin Settings");
    }

    String host = asString(response.get("globalSmtpHost"));
    Integer port = asInteger(response.get("globalSmtpPort"));
    boolean secure = asBoolean(response.get("globalSmtpSecure"));
    String from = asString(response.get("globalSmtpFrom"));
    if (isBlank(host) || port == null || port < 1 || port > 65535 || !validAddress(from)) {
      throw incomplete("host, port, or sender address is incomplete in Admin Settings");
    }

    ApplicationSettingsSmtpCredentialsDTO credentials =
        applicationSettingsService
            .getGlobalSmtpCredentials()
            .orElseThrow(() -> incomplete("credentials are unavailable from Admin Settings"));
    if (isBlank(credentials.getGlobalSmtpUsername())
        || isBlank(credentials.getGlobalSmtpPassword())) {
      throw incomplete("credentials are incomplete in Admin Settings");
    }

    return new Settings(
        host,
        port,
        secure,
        credentials.getGlobalSmtpUsername(),
        credentials.getGlobalSmtpPassword(),
        from);
  }

  private static IllegalStateException incomplete(String detail) {
    return new IllegalStateException(
        "Platform SMTP is not configured in Admin Settings: " + detail);
  }

  private static String normalizeBaseUrl(String value) {
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  private static boolean validAddress(String value) {
    if (isBlank(value)) {
      return false;
    }
    try {
      new InternetAddress(value.trim(), true).validate();
      return true;
    } catch (AddressException exception) {
      return false;
    }
  }

  private static boolean asBoolean(Object raw) {
    Object value = unwrap(raw);
    return value instanceof Boolean
        ? (Boolean) value
        : value instanceof String && "true".equalsIgnoreCase((String) value);
  }

  private static String asString(Object raw) {
    Object value = unwrap(raw);
    return nonNull(value) ? String.valueOf(value).trim() : null;
  }

  private static Integer asInteger(Object raw) {
    Object value = unwrap(raw);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String && isNotBlank((String) value)) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException exception) {
        return null;
      }
    }
    return null;
  }

  private static Object unwrap(Object raw) {
    return raw instanceof Map<?, ?> ? ((Map<String, Object>) raw).get("value") : raw;
  }

  public record Settings(
      String host, int port, boolean secure, String username, String password, String from) {}
}
