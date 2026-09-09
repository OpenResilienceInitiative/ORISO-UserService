package de.caritas.cob.userservice.api.service.email;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteSmtpSettings;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Shared strict SMTP resolution for invites and notification mails. */
@Component
public class GlobalSmtpSettingsResolver {
  private final RestTemplate restTemplate;
  private final ApplicationSettingsService applicationSettingsService;
  private final String consultingTypeServiceApiUrl;
  private final String configuredSmtpUsername;
  private final String configuredSmtpPassword;

  public GlobalSmtpSettingsResolver(
      RestTemplate restTemplate,
      ApplicationSettingsService applicationSettingsService,
      @Value("${consulting.type.service.api.url:}") String consultingTypeServiceApiUrl,
      @Value("${smtp.user:}") String configuredSmtpUsername,
      @Value("${smtp.password:}") String configuredSmtpPassword) {
    this.restTemplate = restTemplate;
    this.applicationSettingsService = applicationSettingsService;
    this.consultingTypeServiceApiUrl = consultingTypeServiceApiUrl;
    this.configuredSmtpUsername = configuredSmtpUsername;
    this.configuredSmtpPassword = configuredSmtpPassword;
  }

  /**
   * Resolves the global SMTP settings or throws an {@link SmtpSendException} whose message names
   * the exact defect (#1006, "loud failure"): unreachable or erroring settings endpoint, the
   * disabled/missing toggle or missing field by name, an invalid port, or missing credentials with
   * both remedies. The detailed message goes to the server log only; the API response carries just
   * the coarse {@link SmtpSendException.Category}. Messages must never contain secret values.
   */
  @SuppressWarnings("unchecked")
  public InviteSmtpSettings resolve() {
    if (isBlank(consultingTypeServiceApiUrl)) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          "Invite mail not sent: 'consulting.type.service.api.url' is not configured on"
              + " UserService, so the global SMTP settings cannot be resolved");
    }
    String settingsUrl = normalizeBaseUrl(consultingTypeServiceApiUrl) + "/settings";

    Map<String, Object> settingsResponse;
    try {
      settingsResponse = restTemplate.getForObject(settingsUrl, Map.class);
    } catch (org.springframework.web.client.RestClientResponseException exception) {
      // Review 3893231991: an HTTP error means the endpoint WAS reached — say so, with the status.
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          "Invite mail not sent: the ConsultingTypeService /settings endpoint responded with HTTP "
              + exception.getStatusCode().value(),
          exception);
    } catch (org.springframework.web.client.ResourceAccessException exception) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          "Invite mail not sent: the ConsultingTypeService /settings endpoint could not be reached"
              + " ("
              + exception.getClass().getSimpleName()
              + ")",
          exception);
    } catch (Exception exception) {
      // Conversion or other unexpected failures get their own case instead of "unreachable".
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          "Invite mail not sent: the ConsultingTypeService /settings response could not be"
              + " processed ("
              + exception.getClass().getSimpleName()
              + ")",
          exception);
    }
    if (settingsResponse == null || settingsResponse.isEmpty()) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_SETTINGS_UNAVAILABLE,
          "Invite mail not sent: the ConsultingTypeService /settings endpoint returned an empty"
              + " payload");
    }

    Boolean systemEmailsEnabled =
        asBooleanSettingValue(settingsResponse.get("globalFeatureSystemNotificationEmailsEnabled"));
    Boolean smtpEnabled = asBooleanSettingValue(settingsResponse.get("globalSmtpEnabled"));
    String host = asStringSettingValue(settingsResponse.get("globalSmtpHost"));
    Boolean secure = asBooleanSettingValue(settingsResponse.get("globalSmtpSecure"));
    String from = asStringSettingValue(settingsResponse.get("globalSmtpFrom"));

    var problems = new java.util.ArrayList<String>();
    // Review 3893223709: an absent or malformed toggle must not masquerade as "disabled".
    if (systemEmailsEnabled == null) {
      problems.add("globalFeatureSystemNotificationEmailsEnabled is missing or not a boolean");
    } else if (!systemEmailsEnabled) {
      problems.add("globalFeatureSystemNotificationEmailsEnabled is disabled");
    }
    if (smtpEnabled == null) {
      problems.add("globalSmtpEnabled is missing or not a boolean");
    } else if (!smtpEnabled) {
      problems.add("globalSmtpEnabled is disabled");
    }
    // Review 3893323639: an absent/malformed secure toggle must not silently select STARTTLS.
    if (secure == null) {
      problems.add("globalSmtpSecure is missing or not a boolean");
    }
    if (isBlank(host)) {
      problems.add("globalSmtpHost is missing");
    }
    Integer port = resolvePort(settingsResponse.get("globalSmtpPort"), problems);
    if (isBlank(from)) {
      problems.add("globalSmtpFrom is missing");
    }
    if (!problems.isEmpty()) {
      throw new SmtpSendException(
          SmtpSendException.Category.SMTP_DISABLED_OR_INCOMPLETE,
          "Invite mail not sent: the global SMTP configuration is unusable — "
              + String.join(", ", problems));
    }

    // The public /settings payload deliberately omits the SMTP username and password since the
    // CTS-C01 credential-leak fix, so they can never be read from there.
    String username = configuredSmtpUsername;
    String password = configuredSmtpPassword;
    if (isBlank(username) || isBlank(password)) {
      var credentials = applicationSettingsService.getGlobalSmtpCredentials();
      if (credentials.isEmpty()) {
        throw new SmtpSendException(
            SmtpSendException.Category.SMTP_CREDENTIALS_MISSING,
            "Invite mail not sent: no SMTP credentials available — set SMTP_USER and SMTP_PASSWORD"
                + " on the UserService deployment (the supported configuration), or the request"
                + " must carry a platform-admin token for the guarded credentials endpoint (see"
                + " the UserService log for the credential lookup outcome)");
      }
      username = credentials.get().getGlobalSmtpUsername();
      password = credentials.get().getGlobalSmtpPassword();
    }

    return new InviteSmtpSettings(host, port, secure, username, password, from);
  }

  /**
   * Review 3893223709: nullable on purpose — {@code null} means "missing or not a boolean", which
   * must not be conflated with an explicit {@code false}.
   */
  private Boolean asBooleanSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String string) {
      if ("true".equalsIgnoreCase(string.trim())) {
        return Boolean.TRUE;
      }
      if ("false".equalsIgnoreCase(string.trim())) {
        return Boolean.FALSE;
      }
    }
    return null;
  }

  private String asStringSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    return nonNull(value) ? String.valueOf(value).trim() : null;
  }

  /**
   * Review 3893223709: validates the port as an integral TCP port in 1..65535 instead of letting 0,
   * negative, out-of-range or fractional values fail later in transport. Adds the field-specific
   * problem and returns {@code null} when the value is unusable.
   */
  private Integer resolvePort(Object raw, java.util.List<String> problems) {
    Object value = unwrapSettingValue(raw);
    if (value instanceof Number number) {
      double asDouble = number.doubleValue();
      if (asDouble == Math.rint(asDouble) && asDouble >= 1 && asDouble <= 65535) {
        return (int) asDouble;
      }
      problems.add("globalSmtpPort is not a valid TCP port (1-65535)");
      return null;
    }
    if (value instanceof String string && isNotBlank(string)) {
      try {
        int parsed = Integer.parseInt(string.trim());
        if (parsed >= 1 && parsed <= 65535) {
          return parsed;
        }
        problems.add("globalSmtpPort is not a valid TCP port (1-65535)");
        return null;
      } catch (NumberFormatException exception) {
        // falls through to the "missing or not a number" problem below
      }
    }
    problems.add("globalSmtpPort is missing or not a number");
    return null;
  }

  @SuppressWarnings("unchecked")
  private Object unwrapSettingValue(Object raw) {
    if (raw instanceof Map<?, ?> map) {
      return ((Map<String, Object>) map).get("value");
    }
    return raw;
  }

  private static String normalizeBaseUrl(String value) {
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
