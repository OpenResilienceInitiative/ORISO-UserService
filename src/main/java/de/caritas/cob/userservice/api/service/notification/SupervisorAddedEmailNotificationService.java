package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailMime;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");

  private final @NonNull SystemNotificationEmailSettingsService emailSettingsService;
  private final @NonNull UserService userService;
  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${system.notification.frontend.base-url:https://app.oriso.org}")
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
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      // Neither the case reference nor a session-specific route reaches the advice seeker's
      // copy: both name what happened as precisely as the anonymised statement text refuses to.
      sendEmailSafely(
          smtpSettings,
          recipientUser.getEmail(),
          renderTeamChange(
              languageCodeOf(recipientUser),
              askerStatementSupervisorJoined(languageCodeOf(recipientUser)),
              appUrl,
              appUrl,
              null,
              themeColor));
    }

    if (hasValidConsultantEmail(supervisor)) {
      sendEmailSafely(
          smtpSettings,
          supervisor.getEmail(),
          renderTeamChange(
              languageCodeOf(supervisor),
              staffStatementSupervisorAdded(languageCodeOf(supervisor)),
              appUrl,
              consultantChatUrl,
              sessionId,
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
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      sendEmailSafely(
          smtpSettings,
          recipientUser.getEmail(),
          renderTeamChange(
              languageCodeOf(recipientUser),
              askerStatementSupervisorLeft(languageCodeOf(recipientUser)),
              appUrl,
              appUrl,
              null,
              themeColor));
    }

    if (hasValidConsultantEmail(supervisor)) {
      sendEmailSafely(
          smtpSettings,
          supervisor.getEmail(),
          renderTeamChange(
              languageCodeOf(supervisor),
              staffStatementSupervisorRemoved(languageCodeOf(supervisor)),
              appUrl,
              consultantChatUrl,
              sessionId,
              themeColor));
    }
  }

  @Async
  public void notifyEmailAddressChanged(
      String username, String newEmail, Long tenantId, TenantData tenantData, String accessToken) {
    if (!isNotBlank(newEmail) || !isNotBlank(username) || tenantId == null) {
      return;
    }
    var smtpSettings = resolveSmtpSettings(tenantId, accessToken);
    if (smtpSettings == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(smtpSettings);
    sendEmailSafely(smtpSettings, newEmail, renderEmailChanged(username, appUrl, themeColor));
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
        && (emailDummySuffix == null || !user.getEmail().endsWith(emailDummySuffix));
  }

  private boolean hasValidConsultantEmail(Consultant consultant) {
    return consultant != null
        && isNotBlank(consultant.getEmail())
        && (emailDummySuffix == null || !consultant.getEmail().endsWith(emailDummySuffix));
  }

  private void sendEmailSafely(
      SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings,
      String recipientEmail,
      OrisoEmailRenderer.RenderedEmail email) {
    try {
      sendDirectSmtpHtmlEmail(smtpSettings, recipientEmail, email);
    } catch (Exception ex) {
      log.error(
          "Failed to send system notification email to {} with subject '{}'",
          recipientEmail,
          email.subject(),
          ex);
    }
  }

  private void sendDirectSmtpHtmlEmail(
      SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings smtpSettings,
      String recipientEmail,
      OrisoEmailRenderer.RenderedEmail email)
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

    jakarta.mail.Session session =
        jakarta.mail.Session.getInstance(
            props,
            new Authenticator() {
              @Override
              protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                    smtpSettings.getUsername(), smtpSettings.getPassword());
              }
            });

    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(smtpSettings.getFrom()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail));
    message.setSubject(email.subject(), "UTF-8");
    message.setContent(OrisoEmailMime.alternative(email));
    log.info("Sending direct SMTP system notification email to {}", recipientEmail);
    Transport.send(message);
  }

  private SystemNotificationEmailSettingsService.SupervisorAddedEmailSettings resolveSmtpSettings(
      Long tenantId, String accessToken) {
    if (tenantId == null) {
      return null;
    }
    return emailSettingsService
        .resolveSupervisorAddedEmailSettings(tenantId, accessToken)
        .orElse(null);
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
              .map(
                  de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO
                      ::getValue)
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

  /**
   * Renders a team-change mail from the design system.
   *
   * <p>Replaces the hand-written 620px Arial card this class used to build inline. The five
   * parameters it took — headline, body, button text, footer text, theme colour — were the design
   * system's card written a second time, in string concatenation, where nobody could review it.
   */
  // Package-private so the anonymity rule can be asserted rather than assumed.
  //
  // appBaseUrl and ctaUrl are deliberately separate: appBaseUrl is always the plain app root,
  // used to build the settings/privacy/imprint/unsubscribe links, so those never inherit a
  // session-specific path. ctaUrl is where the button goes — the deep link into the session for
  // a counsellor's copy (operational detail is fine there), but the same plain app root for an
  // advice seeker's copy. A null sessionId renders as "—", the same "no reference" marker already
  // used when a change has no session at all.
  OrisoEmailRenderer.RenderedEmail renderTeamChange(
      LanguageCode languageCode,
      String statement,
      String appBaseUrl,
      String ctaUrl,
      Long sessionId,
      String themeColor) {
    Map<String, String> values = new LinkedHashMap<>(emailBrand.values(appBaseUrl, themeColor));
    values.put("teamChangeStatement", statement);
    values.put("caseReference", sessionId == null ? "—" : "#" + sessionId);
    values.put("teamChangedAt", LocalDateTime.now().format(TIMESTAMP));
    values.put("appUrl", ctaUrl);
    return emailRenderer.render("team-aenderung", OrisoEmailRenderer.Tone.of(languageCode), values);
  }

  private OrisoEmailRenderer.RenderedEmail renderEmailChanged(
      String username, String appUrl, String themeColor) {
    Map<String, String> values = new LinkedHashMap<>(emailBrand.values(appUrl, themeColor));
    values.put("username", username);
    return emailRenderer.render("email-geaendert", OrisoEmailRenderer.Tone.DE_FORMAL, values);
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
    return user != null && user.getLanguageCode() != null
        ? user.getLanguageCode()
        : LanguageCode.de;
  }

  private LanguageCode languageCodeOf(Consultant consultant) {
    return consultant != null && consultant.getLanguageCode() != null
        ? consultant.getLanguageCode()
        : LanguageCode.de;
  }

  private boolean isGerman(LanguageCode languageCode) {
    return languageCode == null || "de".equalsIgnoreCase(languageCode.name());
  }

  /**
   * What the advice seeker is told.
   *
   * <p>Deliberately names nobody. The previous version put the supervisor's display name and the
   * session number into a mail to an advice seeker, which is exactly what ADR-019 forbids: a mail
   * to an advice seeker states that something happened, and the application — behind a login,
   * encrypted — states what. The counsellor's copy below is unchanged in substance, because a
   * counsellor's mail may carry operational detail.
   */
  String askerStatementSupervisorJoined(LanguageCode languageCode) {
    return isGerman(languageCode)
        ? "Eine weitere Fachkraft unterstützt Ihre Beratung ab sofort mit."
        : "Another member of staff is now supporting your counselling.";
  }

  String askerStatementSupervisorLeft(LanguageCode languageCode) {
    return isGerman(languageCode)
        ? "Eine Fachkraft unterstützt Ihre Beratung nicht mehr mit."
        : "A member of staff is no longer supporting your counselling.";
  }

  String staffStatementSupervisorAdded(LanguageCode languageCode) {
    return isGerman(languageCode)
        ? "Sie wurden als Supervisor-Berater:in zu diesem Vorgang hinzugefügt."
        : "You were added as supervisor consultant to this case.";
  }

  String staffStatementSupervisorRemoved(LanguageCode languageCode) {
    return isGerman(languageCode)
        ? "Sie wurden als Supervisor-Berater:in aus diesem Vorgang entfernt."
        : "You were removed as supervisor consultant from this case.";
  }
}
