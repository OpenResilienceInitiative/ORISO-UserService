package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  private final @NonNull TenantSystemEmailDeliveryClient deliveryClient;
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
    if (tenantId == null || tenantId <= 0) return;
    String themeColor = DEFAULT_EMAIL_THEME_COLOR;
    String appUrl = resolveAppFrontendUrl(tenantData);
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      // Neither the case reference nor a session-specific route reaches the advice seeker's
      // copy: both name what happened as precisely as the anonymised statement text refuses to.
      sendEmailSafely(
          tenantId,
          TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_ADDED,
          recipientUser.getEmail(),
          renderTeamChange(
              languageCodeOf(recipientUser),
              askerStatementSupervisorJoined(languageCodeOf(recipientUser)),
              appUrl,
              appUrl,
              null,
              themeColor,
              tenantId));
    }

    if (hasValidConsultantEmail(supervisor)) {
      sendEmailSafely(
          tenantId,
          TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_ADDED,
          supervisor.getEmail(),
          renderTeamChange(
              languageCodeOf(supervisor),
              staffStatementSupervisorAdded(languageCodeOf(supervisor)),
              appUrl,
              consultantChatUrl,
              sessionId,
              themeColor,
              tenantId));
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
    if (tenantId == null || tenantId <= 0) return;
    String themeColor = DEFAULT_EMAIL_THEME_COLOR;
    String appUrl = resolveAppFrontendUrl(tenantData);
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      sendEmailSafely(
          tenantId,
          TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_REMOVED,
          recipientUser.getEmail(),
          renderTeamChange(
              languageCodeOf(recipientUser),
              askerStatementSupervisorLeft(languageCodeOf(recipientUser)),
              appUrl,
              appUrl,
              null,
              themeColor,
              tenantId));
    }

    if (hasValidConsultantEmail(supervisor)) {
      sendEmailSafely(
          tenantId,
          TenantSystemEmailDeliveryClient.Purpose.SUPERVISOR_REMOVED,
          supervisor.getEmail(),
          renderTeamChange(
              languageCodeOf(supervisor),
              staffStatementSupervisorRemoved(languageCodeOf(supervisor)),
              appUrl,
              consultantChatUrl,
              sessionId,
              themeColor,
              tenantId));
    }
  }

  @Async
  public void notifyEmailAddressChanged(
      String username, String newEmail, Long tenantId, TenantData tenantData, String accessToken) {
    if (!isNotBlank(newEmail) || !isNotBlank(username) || tenantId == null || tenantId <= 0) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    sendEmailSafely(
        tenantId,
        TenantSystemEmailDeliveryClient.Purpose.EMAIL_ADDRESS_CHANGED,
        newEmail,
        renderEmailChanged(username, appUrl, tenantId));
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
      long tenantId,
      TenantSystemEmailDeliveryClient.Purpose purpose,
      String recipientEmail,
      OrisoEmailRenderer.RenderedEmail email) {
    try {
      if (!deliveryClient.send(tenantId, purpose, recipientEmail, email)) {
        log.info("Tenant system email disabled for tenant {} and purpose {}", tenantId, purpose);
      }
    } catch (RuntimeException exception) {
      log.error(
          "Tenant system email delivery unconfirmed for tenant {} and purpose {}",
          tenantId,
          purpose);
    }
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
    return renderTeamChange(
        languageCode,
        statement,
        appBaseUrl,
        ctaUrl,
        sessionId,
        themeColor,
        TenantContext.getCurrentTenant());
  }

  private OrisoEmailRenderer.RenderedEmail renderTeamChange(
      LanguageCode languageCode,
      String statement,
      String appBaseUrl,
      String ctaUrl,
      Long sessionId,
      String themeColor,
      Long tenantId) {
    Map<String, String> values =
        new LinkedHashMap<>(emailBrand.valuesForTenant(appBaseUrl, tenantId));
    values.put("teamChangeStatement", statement);
    values.put("caseReference", sessionId == null ? "—" : "#" + sessionId);
    values.put("teamChangedAt", LocalDateTime.now().format(TIMESTAMP));
    values.put("appUrl", ctaUrl);
    return emailRenderer.render("team-aenderung", OrisoEmailRenderer.Tone.of(languageCode), values);
  }

  private OrisoEmailRenderer.RenderedEmail renderEmailChanged(
      String username, String appUrl, Long tenantId) {
    Map<String, String> values = new LinkedHashMap<>(emailBrand.valuesForTenant(appUrl, tenantId));
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
