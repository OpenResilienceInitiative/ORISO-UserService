package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Sends account-invite mails via the platform's global SMTP settings with a strict
 * receipt-after-send contract (TEN-INV-U6, #890): either the SMTP server accepted the message and
 * an {@link InviteMailSendReceipt} is returned, or an {@link SmtpSendException} propagates. There
 * is no silent-failure path — a caller that does not receive a receipt must never persist SENT.
 *
 * <p>Settings resolution mirrors {@link
 * de.caritas.cob.userservice.api.service.auth.PasswordResetService}: connection settings come from
 * the public ConsultingTypeService {@code /settings} payload (which deliberately omits credentials
 * since CTS-C01), the credentials from the operator-provided {@code smtp.user}/{@code
 * smtp.password} properties, falling back to the super-admin-guarded credentials endpoint when the
 * request context allows it.
 */
@Slf4j
@Service
public class InviteMailDispatchService {

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull ApplicationSettingsService applicationSettingsService;
  private final @NonNull InviteMailTransport inviteMailTransport;
  private final String consultingTypeServiceApiUrl;
  private final String configuredSmtpUsername;
  private final String configuredSmtpPassword;

  public InviteMailDispatchService(
      @NonNull RestTemplate restTemplate,
      @NonNull ApplicationSettingsService applicationSettingsService,
      @NonNull InviteMailTransport inviteMailTransport,
      @Value("${consulting.type.service.api.url:}") String consultingTypeServiceApiUrl,
      @Value("${smtp.user:}") String configuredSmtpUsername,
      @Value("${smtp.password:}") String configuredSmtpPassword) {
    this.restTemplate = restTemplate;
    this.applicationSettingsService = applicationSettingsService;
    this.inviteMailTransport = inviteMailTransport;
    this.consultingTypeServiceApiUrl = consultingTypeServiceApiUrl;
    this.configuredSmtpUsername = configuredSmtpUsername;
    this.configuredSmtpPassword = configuredSmtpPassword;
  }

  /**
   * Sends the given invite mail.
   *
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the global SMTP settings are unavailable/incomplete or the message
   *     could not be handed over to the SMTP server
   */
  public InviteMailSendReceipt send(String recipient, String subject, String htmlBody) {
    InviteSmtpSettings settings =
        resolveGlobalSmtpSettings()
            .orElseThrow(
                () ->
                    new SmtpSendException(
                        "Global SMTP settings are unavailable or incomplete — invite mail not"
                            + " sent"));
    return inviteMailTransport.send(settings, recipient, subject, htmlBody);
  }

  @SuppressWarnings("unchecked")
  private Optional<InviteSmtpSettings> resolveGlobalSmtpSettings() {
    if (isBlank(consultingTypeServiceApiUrl)) {
      log.warn("Invite mail dispatch: 'consulting.type.service.api.url' is not configured");
      return Optional.empty();
    }
    try {
      String settingsUrl = normalizeBaseUrl(consultingTypeServiceApiUrl) + "/settings";
      Map<String, Object> settingsResponse = restTemplate.getForObject(settingsUrl, Map.class);
      if (settingsResponse == null || settingsResponse.isEmpty()) {
        return Optional.empty();
      }

      boolean systemEmailsEnabled =
          asBooleanSettingValue(
              settingsResponse.get("globalFeatureSystemNotificationEmailsEnabled"));
      boolean smtpEnabled = asBooleanSettingValue(settingsResponse.get("globalSmtpEnabled"));
      String host = asStringSettingValue(settingsResponse.get("globalSmtpHost"));
      Integer port = asIntSettingValue(settingsResponse.get("globalSmtpPort"));
      boolean secure = asBooleanSettingValue(settingsResponse.get("globalSmtpSecure"));
      String from = asStringSettingValue(settingsResponse.get("globalSmtpFrom"));

      if (!systemEmailsEnabled || !smtpEnabled || isBlank(host) || port == null || isBlank(from)) {
        log.warn("Invite mail dispatch: global SMTP settings are incomplete or disabled");
        return Optional.empty();
      }

      // The public /settings payload deliberately omits the SMTP username and password since the
      // CTS-C01 credential-leak fix, so they can never be read from there.
      String username = configuredSmtpUsername;
      String password = configuredSmtpPassword;
      if (isBlank(username) || isBlank(password)) {
        var credentials = applicationSettingsService.getGlobalSmtpCredentials();
        if (credentials.isEmpty()) {
          log.warn(
              "Invite mail not sent: no SMTP credentials available. Set SMTP_USER and"
                  + " SMTP_PASSWORD on UserService or send with a super-admin token.");
          return Optional.empty();
        }
        username = credentials.get().getGlobalSmtpUsername();
        password = credentials.get().getGlobalSmtpPassword();
      }

      return Optional.of(new InviteSmtpSettings(host, port, secure, username, password, from));
    } catch (Exception exception) {
      log.warn(
          "Invite mail dispatch: could not resolve global SMTP settings ({})",
          exception.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private boolean asBooleanSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof String string) {
      return "true".equalsIgnoreCase(string);
    }
    return false;
  }

  private String asStringSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    return nonNull(value) ? String.valueOf(value).trim() : null;
  }

  private Integer asIntSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && isNotBlank(string)) {
      try {
        return Integer.parseInt(string.trim());
      } catch (NumberFormatException exception) {
        return null;
      }
    }
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
