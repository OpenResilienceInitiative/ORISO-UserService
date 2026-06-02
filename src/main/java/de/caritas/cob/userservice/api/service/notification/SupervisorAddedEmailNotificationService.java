package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.net.URI;
import java.util.List;
import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupervisorAddedEmailNotificationService {
  private static final String DEFAULT_EMAIL_THEME_COLOR = "#0f3b8f";

  private final @NonNull SystemNotificationEmailSettingsService emailSettingsService;
  private final @NonNull UserService userService;
  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${system.notification.frontend.base-url:https://app.oriso-dev.site}")
  private String publicFrontendBaseUrl;

  @Value("${identity.email-dummy-suffix}")
  private String emailDummySuffix;

  @Async
  public void notifySupervisorAdded(
      User sessionUser,
      Consultant supervisor,
      String supervisorDisplayName,
      Long sessionId,
      TenantData tenantData,
      String accessToken) {
    Long tenantId =
        tenantData != null ? tenantData.getTenantId() : resolveTenantId(sessionUser, supervisor);
    var smtpSettings = resolveSmtpSettings(tenantId, accessToken);
    if (smtpSettings == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(smtpSettings);
    String userChatUrl = buildSessionUrl(appUrl, sessionId, false);
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      LocalizedEmailContent localizedEmail =
          localizeSupervisorAssignmentAdded(
              languageCodeOf(recipientUser), supervisorDisplayName, sessionId);
      sendEmailSafely(
          smtpSettings,
          recipientUser.getEmail(),
          localizedEmail.subject,
          buildHtmlEmail(
              localizedEmail.headline,
              localizedEmail.body,
              userChatUrl,
              localizedEmail.buttonText,
              localizedEmail.footerText,
              themeColor));
    }

    if (hasValidConsultantEmail(supervisor)) {
      LocalizedEmailContent localizedEmail =
          localizeSupervisorAddedForSupervisor(languageCodeOf(supervisor), sessionId);
      sendEmailSafely(
          smtpSettings,
          supervisor.getEmail(),
          localizedEmail.subject,
          buildHtmlEmail(
              localizedEmail.headline,
              localizedEmail.body,
              consultantChatUrl,
              localizedEmail.buttonText,
              localizedEmail.footerText,
              themeColor));
    }
  }

  @Async
  public void notifySupervisorRemoved(
      User sessionUser,
      Consultant supervisor,
      String supervisorDisplayName,
      Long sessionId,
      TenantData tenantData,
      String accessToken) {
    Long tenantId =
        tenantData != null ? tenantData.getTenantId() : resolveTenantId(sessionUser, supervisor);
    var smtpSettings = resolveSmtpSettings(tenantId, accessToken);
    if (smtpSettings == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(smtpSettings);
    String userChatUrl = buildSessionUrl(appUrl, sessionId, false);
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      LocalizedEmailContent localizedEmail =
          localizeSupervisorAssignmentRemoved(
              languageCodeOf(recipientUser), supervisorDisplayName, sessionId);
      sendEmailSafely(
          smtpSettings,
          recipientUser.getEmail(),
          localizedEmail.subject,
          buildHtmlEmail(
              localizedEmail.headline,
              localizedEmail.body,
              userChatUrl,
              localizedEmail.buttonText,
              localizedEmail.footerText,
              themeColor));
    }

    if (hasValidConsultantEmail(supervisor)) {
      LocalizedEmailContent localizedEmail =
          localizeSupervisorRemovedForSupervisor(languageCodeOf(supervisor), sessionId);
      sendEmailSafely(
          smtpSettings,
          supervisor.getEmail(),
          localizedEmail.subject,
          buildHtmlEmail(
              localizedEmail.headline,
              localizedEmail.body,
              consultantChatUrl,
              localizedEmail.buttonText,
              localizedEmail.footerText,
              themeColor));
    }
  }

  @Async
  public void notifyEmailAddressChanged(
      String username,
      String newEmail,
      Long tenantId,
      TenantData tenantData,
      String accessToken) {
    if (!isNotBlank(newEmail) || !isNotBlank(username) || tenantId == null) {
      return;
    }
    var smtpSettings = resolveSmtpSettings(tenantId, accessToken);
    if (smtpSettings == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(smtpSettings);
    LocalizedEmailContent localizedEmail = localizeEmailUpdate();
    sendEmailSafely(
        smtpSettings,
        newEmail,
        localizedEmail.subject,
        buildHtmlEmail(
            localizedEmail.headline,
            String.format(localizedEmail.body, username),
            appUrl,
            localizedEmail.buttonText,
            localizedEmail.footerText,
            themeColor));
  }

  private Long resolveTenantId(User sessionUser, Consultant supervisor) {
    if (sessionUser != null && sessionUser.getTenantId() != null) {
      return sessionUser.getTenantId();
    }
    return supervisor != null ? supervisor.getTenantId() : null;
  }

  private User resolveUserWithEmail(User user) {
    if (user == null || hasValidUserEmail(user) || !isNotBlank(user.getUserId())) {
      return user;
    }
    return userService.getUser(user.getUserId()).orElse(user);
  }

  private boolean hasValidUserEmail(User user) {
    return user != null
        && isNotBlank(user.getEmail())
        && !user.getEmail().endsWith(emailDummySuffix);
  }

  private boolean hasValidConsultantEmail(Consultant consultant) {
    return consultant != null
        && isNotBlank(consultant.getEmail())
        && !consultant.getEmail().endsWith(emailDummySuffix);
  }

  private void sendEmailSafely(
      SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings,
      String recipientEmail,
      String subject,
      String htmlContent) {
    try {
      sendDirectSmtpHtmlEmail(smtpSettings, recipientEmail, subject, htmlContent);
    } catch (Exception ex) {
      log.error(
          "Failed to send system notification email to {} with subject '{}'",
          recipientEmail,
          subject,
          ex);
    }
  }

  private void sendDirectSmtpHtmlEmail(
      SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings,
      String recipientEmail,
      String subject,
      String htmlContent)
      throws Exception {
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.host", smtpSettings.getHost());
    props.put("mail.smtp.port", String.valueOf(smtpSettings.getPort()));
    if (smtpSettings.isSecure()) {
      props.put("mail.smtp.ssl.enable", "true");
    } else {
      props.put("mail.smtp.starttls.enable", "true");
    }

    javax.mail.Session session =
        javax.mail.Session.getInstance(
            props,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                    smtpSettings.getUsername(), smtpSettings.getPassword());
              }
            });

    Message message = new MimeMessage(session);
    message.setFrom(new InternetAddress(smtpSettings.getFrom()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
    message.setSubject(subject);
    message.setContent(htmlContent, "text/html; charset=UTF-8");
    log.info("Sending direct SMTP system notification email to {}", recipientEmail);
    Transport.send(message);
  }

  private SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings resolveSmtpSettings(
      Long tenantId, String accessToken) {
    if (tenantId == null) {
      return null;
    }
    return emailSettingsService.resolveSupervisorAddedEmailSettings(tenantId, accessToken).orElse(null);
  }

  private String resolveAppFrontendUrl(TenantData tenantData) {
    if (tenantData == null) {
      return sanitizeFrontendUrl(applicationBaseUrl);
    }
    try {
      TenantContext.setCurrentTenantData(tenantData);
      List<de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO> attributes =
          tenantTemplateSupplier.getTemplateAttributes();
      String resolved =
          attributes.stream()
              .filter(entry -> "url".equals(entry.getKey()))
              .map(de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO::getValue)
              .filter(value -> isNotBlank(value))
              .findFirst()
              .orElse(applicationBaseUrl);
      return sanitizeFrontendUrl(resolved);
    } catch (Exception ex) {
      return sanitizeFrontendUrl(applicationBaseUrl);
    } finally {
      TenantContext.clear();
    }
  }

  private String sanitizeFrontendUrl(String url) {
    if (!isNotBlank(url) || isLocalUrl(url)) {
      return publicFrontendBaseUrl;
    }
    return url;
  }

  private boolean isLocalUrl(String url) {
    try {
      URI uri = URI.create(url.trim());
      String host = uri.getHost();
      return host == null
          || "localhost".equalsIgnoreCase(host)
          || "127.0.0.1".equals(host)
          || "::1".equals(host);
    } catch (Exception ex) {
      return true;
    }
  }

  private String buildHtmlEmail(
      String headline,
      String bodyText,
      String appUrl,
      String buttonText,
      String footerText,
      String themeColor) {
    String resolvedThemeColor = resolveHexColor(themeColor);
    return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f6f7fb;font-family:Arial,sans-serif;\">"
        + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:24px 0;\">"
        + "<tr><td align=\"center\">"
        + "<table role=\"presentation\" width=\"620\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:620px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">"
        + "<tr><td style=\"background:"
        + escapeHtml(resolvedThemeColor)
        + ";padding:18px 24px;color:#ffffff;font-size:20px;font-weight:700;\">ORISO</td></tr>"
        + "<tr><td style=\"padding:28px 24px 8px 24px;color:#111827;font-size:24px;line-height:32px;font-weight:700;\">"
        + escapeHtml(headline)
        + "</td></tr>"
        + "<tr><td style=\"padding:0 24px 18px 24px;color:#374151;font-size:16px;line-height:24px;\">"
        + escapeHtml(bodyText)
        + "</td></tr>"
        + "<tr><td style=\"padding:0 24px 24px 24px;\">"
        + "<a href=\""
        + escapeHtml(appUrl)
        + "\" style=\"display:inline-block;background:"
        + escapeHtml(resolvedThemeColor)
        + ";color:#ffffff;text-decoration:none;padding:12px 18px;border-radius:8px;font-weight:700;\">"
        + escapeHtml(buttonText)
        + "</a>"
        + "</td></tr>"
        + "<tr><td style=\"padding:18px 24px;color:#6b7280;font-size:12px;line-height:18px;border-top:1px solid #e5e7eb;\">"
        + escapeHtml(footerText)
        + "</td></tr>"
        + "</table></td></tr></table></body></html>";
  }

  private String buildSessionUrl(String baseUrl, Long sessionId, boolean consultantView) {
    String safeBase = baseUrl == null ? "" : baseUrl.trim();
    if (safeBase.endsWith("/")) {
      safeBase = safeBase.substring(0, safeBase.length() - 1);
    }
    String sessionPath = sessionId == null ? "" : String.valueOf(sessionId);
    String path =
        consultantView
            ? "/sessions/consultant/sessionView/session/" + sessionPath
            : "/sessions/user/view/session/" + sessionPath;
    return safeBase + path;
  }

  private String resolveThemeColor(
      SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings) {
    return resolveHexColor(
        smtpSettings != null ? smtpSettings.getEmailThemeColor() : DEFAULT_EMAIL_THEME_COLOR);
  }

  private String resolveHexColor(String color) {
    if (isNotBlank(color) && color.trim().matches("^#([A-Fa-f0-9]{6})$")) {
      return color.trim();
    }
    return DEFAULT_EMAIL_THEME_COLOR;
  }

  private LanguageCode languageCodeOf(User user) {
    return user != null && user.getLanguageCode() != null ? user.getLanguageCode() : LanguageCode.de;
  }

  private LanguageCode languageCodeOf(Consultant consultant) {
    return consultant != null && consultant.getLanguageCode() != null
        ? consultant.getLanguageCode()
        : LanguageCode.de;
  }

  private boolean isGerman(LanguageCode languageCode) {
    return languageCode == null || "de".equalsIgnoreCase(languageCode.name());
  }

  private LocalizedEmailContent localizeSupervisorAssignmentAdded(
      LanguageCode languageCode, String supervisorDisplayName, Long sessionId) {
    String displayName =
        isNotBlank(supervisorDisplayName) ? supervisorDisplayName : "Eine Beraterin/Ein Berater";
    String session = String.valueOf(sessionId);
    if (!isGerman(languageCode)) {
      return new LocalizedEmailContent(
          "Supervisor added to your chat",
          "New system notification",
          String.format("%s was added as supervisor consultant to your chat #%s.", displayName, session),
          "Open chat",
          "This message was sent automatically.");
    }
    return new LocalizedEmailContent(
        "Supervisor zu Ihrem Chat hinzugefuegt",
        "Neue Systembenachrichtigung",
        String.format("%s wurde als Supervisor-Berater:in zu Ihrem Chat #%s hinzugefuegt.", displayName, session),
        "Zum Chat",
        "Diese Nachricht wurde automatisch versendet.");
  }

  private LocalizedEmailContent localizeSupervisorAddedForSupervisor(
      LanguageCode languageCode, Long sessionId) {
    String session = String.valueOf(sessionId);
    if (!isGerman(languageCode)) {
      return new LocalizedEmailContent(
          "You were added as supervisor",
          "New system notification",
          String.format("You were added as supervisor consultant to chat #%s.", session),
          "Open chat",
          "This message was sent automatically.");
    }
    return new LocalizedEmailContent(
        "Sie wurden als Supervisor hinzugefuegt",
        "Neue Systembenachrichtigung",
        String.format("Sie wurden als Supervisor-Berater:in zu Chat #%s hinzugefuegt.", session),
        "Zum Chat",
        "Diese Nachricht wurde automatisch versendet.");
  }

  private LocalizedEmailContent localizeSupervisorAssignmentRemoved(
      LanguageCode languageCode, String supervisorDisplayName, Long sessionId) {
    String displayName =
        isNotBlank(supervisorDisplayName) ? supervisorDisplayName : "Eine Beraterin/Ein Berater";
    String session = String.valueOf(sessionId);
    if (!isGerman(languageCode)) {
      return new LocalizedEmailContent(
          "Supervisor removed from your chat",
          "New system notification",
          String.format("%s was removed as supervisor consultant from your chat #%s.", displayName, session),
          "Open chat",
          "This message was sent automatically.");
    }
    return new LocalizedEmailContent(
        "Supervisor aus Ihrem Chat entfernt",
        "Neue Systembenachrichtigung",
        String.format("%s wurde als Supervisor-Berater:in aus Ihrem Chat #%s entfernt.", displayName, session),
        "Zum Chat",
        "Diese Nachricht wurde automatisch versendet.");
  }

  private LocalizedEmailContent localizeSupervisorRemovedForSupervisor(
      LanguageCode languageCode, Long sessionId) {
    String session = String.valueOf(sessionId);
    if (!isGerman(languageCode)) {
      return new LocalizedEmailContent(
          "Supervisor assignment removed",
          "New system notification",
          String.format("You were removed as supervisor consultant from chat #%s.", session),
          "Open chat",
          "This message was sent automatically.");
    }
    return new LocalizedEmailContent(
        "Supervisor-Zuweisung entfernt",
        "Neue Systembenachrichtigung",
        String.format("Sie wurden als Supervisor-Berater:in aus Chat #%s entfernt.", session),
        "Zum Chat",
        "Diese Nachricht wurde automatisch versendet.");
  }

  private LocalizedEmailContent localizeEmailUpdate() {
    return new LocalizedEmailContent(
        "Email updated",
        "E-Mail-Adresse erfolgreich aktualisiert",
        "Die E-Mail-Adresse fuer den Benutzer %s wurde erfolgreich geaendert.",
        "Zum Profil",
        "Diese Nachricht wurde automatisch versendet.");
  }

  @lombok.Value
  private static class LocalizedEmailContent {
    String subject;
    String headline;
    String body;
    String buttonText;
    String footerText;
  }

  private String escapeHtml(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}

