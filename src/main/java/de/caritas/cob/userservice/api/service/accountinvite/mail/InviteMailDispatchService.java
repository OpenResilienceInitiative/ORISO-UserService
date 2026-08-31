package de.caritas.cob.userservice.api.service.accountinvite.mail;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.exception.SmtpSendException;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmail;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmailLayoutRenderer;
import de.caritas.cob.userservice.api.service.email.layout.BrandedEmailRequest;
import de.caritas.cob.userservice.api.service.email.layout.EmailBranding;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import java.util.Map;
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
 *
 * <p>Since ORISO-UserService#914 this service is also the single choke point where the branded
 * layout is applied: callers hand over the <em>authored content</em> and the primary action, never
 * finished markup. Wrapping here (instead of in each caller) is what guarantees that every mail on
 * this path — tenant-admin invite, counsellor invite, resend — is branded, and that the Admin
 * preview endpoint and the dispatcher cannot drift apart. The send contract itself is untouched:
 * receipt after acceptance, {@link SmtpSendException} otherwise.
 */
@Slf4j
@Service
public class InviteMailDispatchService {

  private final @NonNull RestTemplate restTemplate;
  private final @NonNull ApplicationSettingsService applicationSettingsService;
  private final @NonNull InviteMailTransport inviteMailTransport;
  private final @NonNull EmailBrandingResolver emailBrandingResolver;
  private final @NonNull BrandedEmailLayoutRenderer brandedEmailLayoutRenderer;
  private final String consultingTypeServiceApiUrl;
  private final String configuredSmtpUsername;
  private final String configuredSmtpPassword;

  public InviteMailDispatchService(
      @NonNull RestTemplate restTemplate,
      @NonNull ApplicationSettingsService applicationSettingsService,
      @NonNull InviteMailTransport inviteMailTransport,
      @NonNull EmailBrandingResolver emailBrandingResolver,
      @NonNull BrandedEmailLayoutRenderer brandedEmailLayoutRenderer,
      @Value("${consulting.type.service.api.url:}") String consultingTypeServiceApiUrl,
      @Value("${smtp.user:}") String configuredSmtpUsername,
      @Value("${smtp.password:}") String configuredSmtpPassword) {
    this.restTemplate = restTemplate;
    this.applicationSettingsService = applicationSettingsService;
    this.inviteMailTransport = inviteMailTransport;
    this.emailBrandingResolver = emailBrandingResolver;
    this.brandedEmailLayoutRenderer = brandedEmailLayoutRenderer;
    this.consultingTypeServiceApiUrl = consultingTypeServiceApiUrl;
    this.configuredSmtpUsername = configuredSmtpUsername;
    this.configuredSmtpPassword = configuredSmtpPassword;
  }

  /**
   * Sends the given mail content without a call-to-action button and without tenant branding.
   *
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the global SMTP settings are unavailable/incomplete or the message
   *     could not be handed over to the SMTP server
   */
  public InviteMailSendReceipt send(String recipient, String subject, String bodyContent) {
    return send(recipient, subject, bodyContent, null, null, null);
  }

  /**
   * Renders the authored content into the canonical branded layout and sends it as a genuine
   * multipart mail.
   *
   * @param bodyContent the authored template body — plain text or simple markup, sanitised by the
   *     layout renderer; callers must not pass finished HTML
   * @param primaryActionUrl the invite/onboarding link rendered as a button plus a visible
   *     copy-paste fallback line, or {@code null} for mails without an action
   * @param tenantId tenant whose theming should brand the mail, or {@code null} for platform
   *     branding (the normal case for a tenant-admin invite: the tenant does not exist yet)
   * @param language BCP-47 tag selecting the frame wording; {@code null} means German
   * @return a receipt confirming the SMTP server accepted the message
   * @throws SmtpSendException if the global SMTP settings are unavailable/incomplete or the message
   *     could not be handed over to the SMTP server
   */
  public InviteMailSendReceipt send(
      String recipient,
      String subject,
      String bodyContent,
      String primaryActionUrl,
      Long tenantId,
      String language) {
    InviteSmtpSettings smtp = resolveGlobalSmtpSettings();
    BrandedEmail mail =
        renderBrandedMail(subject, bodyContent, primaryActionUrl, tenantId, language);
    return inviteMailTransport.send(smtp, recipient, subject, mail.html(), mail.plainText());
  }

  /**
   * Renders exactly what {@link #send} would transmit. The Admin preview endpoint calls this, so a
   * preview can never show markup the dispatcher would not produce.
   *
   * <p>The palette comes from the tenant theming alone (#914, final decision). The SMTP settings
   * payload also carries {@code globalSmtpEmailThemeColor} ("E-Mail Designfarbe"), but that value
   * is deliberately not read: a transport setting is not a design token, and mixing it in produced
   * mails in a colour the product never uses.
   */
  public BrandedEmail renderBrandedMail(
      String subject, String bodyContent, String primaryActionUrl, Long tenantId, String language) {
    EmailBranding branding = emailBrandingResolver.resolve(tenantId);
    return brandedEmailLayoutRenderer.render(
        branding, new BrandedEmailRequest(subject, bodyContent, primaryActionUrl, null, language));
  }

  /**
   * Resolves the global SMTP settings or throws an {@link SmtpSendException} whose message names
   * the exact defect (#1006, "loud failure"): unreachable settings endpoint, the disabled toggle or
   * missing field by name, or missing credentials with both remedies. The message travels to the
   * Admin UI via the SMTP exception handler, so it must never contain secret values.
   */
  @SuppressWarnings("unchecked")
  private InviteSmtpSettings resolveGlobalSmtpSettings() {
    if (isBlank(consultingTypeServiceApiUrl)) {
      throw new SmtpSendException(
          "Invite mail not sent: 'consulting.type.service.api.url' is not configured on"
              + " UserService, so the global SMTP settings cannot be resolved");
    }
    String settingsUrl = normalizeBaseUrl(consultingTypeServiceApiUrl) + "/settings";

    Map<String, Object> settingsResponse;
    try {
      settingsResponse = restTemplate.getForObject(settingsUrl, Map.class);
    } catch (Exception exception) {
      throw new SmtpSendException(
          "Invite mail not sent: the ConsultingTypeService /settings endpoint could not be reached"
              + " ("
              + exception.getClass().getSimpleName()
              + ")",
          exception);
    }
    if (settingsResponse == null || settingsResponse.isEmpty()) {
      throw new SmtpSendException(
          "Invite mail not sent: the ConsultingTypeService /settings endpoint returned an empty"
              + " payload");
    }

    boolean systemEmailsEnabled =
        asBooleanSettingValue(settingsResponse.get("globalFeatureSystemNotificationEmailsEnabled"));
    boolean smtpEnabled = asBooleanSettingValue(settingsResponse.get("globalSmtpEnabled"));
    String host = asStringSettingValue(settingsResponse.get("globalSmtpHost"));
    Integer port = asIntSettingValue(settingsResponse.get("globalSmtpPort"));
    boolean secure = asBooleanSettingValue(settingsResponse.get("globalSmtpSecure"));
    String from = asStringSettingValue(settingsResponse.get("globalSmtpFrom"));

    var problems = new java.util.ArrayList<String>();
    if (!systemEmailsEnabled) {
      problems.add("globalFeatureSystemNotificationEmailsEnabled is disabled");
    }
    if (!smtpEnabled) {
      problems.add("globalSmtpEnabled is disabled");
    }
    if (isBlank(host)) {
      problems.add("globalSmtpHost is missing");
    }
    if (port == null) {
      problems.add("globalSmtpPort is missing or not a number");
    }
    if (isBlank(from)) {
      problems.add("globalSmtpFrom is missing");
    }
    if (!problems.isEmpty()) {
      throw new SmtpSendException(
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
