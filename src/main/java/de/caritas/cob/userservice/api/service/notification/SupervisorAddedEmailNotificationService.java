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
import java.util.function.Supplier;
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

  private final @NonNull TenantSystemEmailRouteService emailRoutes;
  private final @NonNull TenantSystemEmailDelivery emailDelivery;
  private final @NonNull UserService userService;
  private final @NonNull TenantTemplateSupplier tenantTemplateSupplier;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;

  @Value("${system.notification.frontend.base-url}")
  private String publicFrontendBaseUrl;

  @Value("${identity.email-dummy-suffix}")
  private String emailDummySuffix;

  @Async
  public void notifySupervisorAdded(
      User sessionUser,
      Consultant supervisor,
      Long sessionId,
      TenantData tenantData,
      String accessToken) {
    Long tenantId =
        tenantData != null ? tenantData.getTenantId() : resolveTenantId(sessionUser, supervisor);
    var route = resolveRoute(tenantId);
    if (route == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(route);
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      // Neither the case reference nor a session-specific route reaches the advice seeker's
      // copy: both name what happened as precisely as the anonymised statement text refuses to.
      renderAndSendTeamChange(
          tenantId,
          route,
          TenantSystemEmailDelivery.Purpose.SUPERVISOR_ADDED,
          recipientUser.getEmail(),
          "advice seeker",
          () -> {
            LanguageCode language = languageCodeOf(recipientUser);
            return renderTeamChange(
                language,
                askerStatementSupervisorJoined(language),
                appUrl,
                appUrl,
                null,
                themeColor);
          });
    }

    if (hasValidConsultantEmail(supervisor)) {
      renderAndSendTeamChange(
          tenantId,
          route,
          TenantSystemEmailDelivery.Purpose.SUPERVISOR_ADDED,
          supervisor.getEmail(),
          "counsellor",
          () -> {
            LanguageCode language = languageCodeOf(supervisor);
            return renderTeamChange(
                language,
                staffStatementSupervisorAdded(language),
                appUrl,
                consultantChatUrl,
                sessionId,
                themeColor);
          });
    }
  }

  @Async
  public void notifySupervisorRemoved(
      User sessionUser,
      Consultant supervisor,
      Long sessionId,
      TenantData tenantData,
      String accessToken) {
    Long tenantId =
        tenantData != null ? tenantData.getTenantId() : resolveTenantId(sessionUser, supervisor);
    var route = resolveRoute(tenantId);
    if (route == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(route);
    String consultantChatUrl = buildSessionUrl(appUrl, sessionId, true);

    User recipientUser = resolveUserWithEmail(sessionUser);
    if (hasValidUserEmail(recipientUser)) {
      renderAndSendTeamChange(
          tenantId,
          route,
          TenantSystemEmailDelivery.Purpose.SUPERVISOR_REMOVED,
          recipientUser.getEmail(),
          "advice seeker",
          () -> {
            LanguageCode language = languageCodeOf(recipientUser);
            return renderTeamChange(
                language, askerStatementSupervisorLeft(language), appUrl, appUrl, null, themeColor);
          });
    }

    if (hasValidConsultantEmail(supervisor)) {
      renderAndSendTeamChange(
          tenantId,
          route,
          TenantSystemEmailDelivery.Purpose.SUPERVISOR_REMOVED,
          supervisor.getEmail(),
          "counsellor",
          () -> {
            LanguageCode language = languageCodeOf(supervisor);
            return renderTeamChange(
                language,
                staffStatementSupervisorRemoved(language),
                appUrl,
                consultantChatUrl,
                sessionId,
                themeColor);
          });
    }
  }

  @Async
  public void notifyEmailAddressChanged(
      String username,
      String newEmail,
      Long tenantId,
      TenantData tenantData,
      String accessToken,
      LanguageCode languageCode) {
    if (!isNotBlank(newEmail) || !isNotBlank(username) || tenantId == null) {
      return;
    }
    OrisoEmailRenderer.Tone tone = OrisoEmailRenderer.Tone.of(languageCode);
    var route = resolveRoute(tenantId);
    if (route == null) {
      return;
    }
    String appUrl = resolveAppFrontendUrl(tenantData);
    String themeColor = resolveThemeColor(route);
    sendEmailSafely(
        tenantId,
        route,
        TenantSystemEmailDelivery.Purpose.EMAIL_ADDRESS_CHANGED,
        newEmail,
        renderEmailChanged(username, appUrl, themeColor, tone));
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
      Long tenantId,
      TenantSystemEmailRouteService.Route route,
      TenantSystemEmailDelivery.Purpose purpose,
      String recipientEmail,
      OrisoEmailRenderer.RenderedEmail email) {
    try {
      emailDelivery.send(tenantId, route, purpose, recipientEmail, email);
    } catch (TenantSystemEmailRouteService.ConfigurationException ex) {
      log.error("System notification configuration for tenant {}: {}", tenantId, ex.getMessage());
    } catch (Exception ex) {
      log.error(
          "System notification delivery failed for tenant {}: {}",
          tenantId,
          ex.getClass().getSimpleName());
    }
  }

  private void renderAndSendTeamChange(
      Long tenantId,
      TenantSystemEmailRouteService.Route route,
      TenantSystemEmailDelivery.Purpose purpose,
      String recipientEmail,
      String recipientRole,
      Supplier<OrisoEmailRenderer.RenderedEmail> render) {
    try {
      sendEmailSafely(tenantId, route, purpose, recipientEmail, render.get());
    } catch (Exception ex) {
      log.error("Failed to render team-change notification for {}", recipientRole, ex);
    }
  }

  private TenantSystemEmailRouteService.Route resolveRoute(Long tenantId) {
    if (tenantId == null) {
      return null;
    }
    try {
      return emailRoutes.resolve(tenantId).orElse(null);
    } catch (TenantSystemEmailRouteService.ConfigurationException ex) {
      log.error("System notification configuration for tenant {}: {}", tenantId, ex.getMessage());
      return null;
    } catch (RuntimeException ex) {
      log.error(
          "System notification route failed for tenant {}: {}",
          tenantId,
          ex.getClass().getSimpleName());
      return null;
    }
  }

  private String resolveAppFrontendUrl(TenantData tenantData) {
    if (tenantData == null) {
      return requireFrontendUrl(publicFrontendBaseUrl, "system.notification.frontend.base-url");
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
              .orElseThrow(
                  () ->
                      new TenantSystemEmailRouteService.ConfigurationException(
                          "Tenant email URL is missing"));
      String url = requireFrontendUrl(resolved, "tenant email URL");
      if (isLocalUrl(url)) {
        throw new TenantSystemEmailRouteService.ConfigurationException(
            "tenant email URL must be public");
      }
      return url;
    } finally {
      TenantContext.clear();
    }
  }

  private String requireFrontendUrl(String url, String setting) {
    if (!isNotBlank(url) || !isAbsoluteHttpUrl(url)) {
      throw new TenantSystemEmailRouteService.ConfigurationException(
          setting + " must be an absolute http(s) URL");
    }
    return url;
  }

  private boolean isAbsoluteHttpUrl(String url) {
    try {
      URI uri = URI.create(url.trim());
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private boolean isLocalUrl(String url) {
    try {
      URI uri = URI.create(url.trim());
      String host = uri.getHost();
      return host == null
          || "localhost".equalsIgnoreCase(host)
          || "127.0.0.1".equals(host)
          || "::1".equals(host)
          || "[::1]".equals(host);
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
      String username, String appUrl, String themeColor, OrisoEmailRenderer.Tone tone) {
    Map<String, String> values = new LinkedHashMap<>(emailBrand.values(appUrl, themeColor));
    values.put("username", username);
    return emailRenderer.render("email-geaendert", tone, values);
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

  private String resolveThemeColor(TenantSystemEmailRouteService.Route route) {
    return resolveHexColor(route != null ? route.emailThemeColor() : DEFAULT_EMAIL_THEME_COLOR);
  }

  private String resolveHexColor(String color) {
    if (isNotBlank(color) && color.trim().matches("^#([A-Fa-f0-9]{6})$")) {
      return color.trim();
    }
    return DEFAULT_EMAIL_THEME_COLOR;
  }

  private LanguageCode languageCodeOf(User user) {
    if (user == null || user.getLanguageCode() == null) {
      throw new IllegalArgumentException("Advice seeker language is missing");
    }
    return user.getLanguageCode();
  }

  private LanguageCode languageCodeOf(Consultant consultant) {
    if (consultant == null || consultant.getLanguageCode() == null) {
      throw new IllegalArgumentException("Counsellor language is missing");
    }
    return consultant.getLanguageCode();
  }

  /** The seeker statement deliberately names nobody and carries no case reference. */
  String askerStatementSupervisorJoined(LanguageCode languageCode) {
    return switch (OrisoEmailRenderer.Tone.of(languageCode)) {
      case DE_FORMAL, DE_INFORMAL ->
          "Eine weitere Fachkraft unterstützt Ihre Beratung ab sofort mit.";
      case EN -> "Another member of staff is now supporting your counselling.";
      case FR -> "Un autre professionnel participe désormais à votre accompagnement.";
      case RU -> "Теперь в вашей консультации участвует ещё один специалист.";
      case TI -> "ካልእ ሰራሕተኛ ካብ ሕጂ ንደሓር ኣብ ምኽርኹም ይሕግዝ ኣሎ።";
      case TR -> "Artık başka bir uzman da danışmanlığınıza destek veriyor.";
    };
  }

  String askerStatementSupervisorLeft(LanguageCode languageCode) {
    return switch (OrisoEmailRenderer.Tone.of(languageCode)) {
      case DE_FORMAL, DE_INFORMAL -> "Eine Fachkraft unterstützt Ihre Beratung nicht mehr mit.";
      case EN -> "A member of staff is no longer supporting your counselling.";
      case FR -> "Un professionnel ne participe plus à votre accompagnement.";
      case RU -> "Один из специалистов больше не участвует в вашей консультации.";
      case TI -> "ሓደ ሰራሕተኛ ኣብ ምኽርኹም ደጊም ኣይሕግዝን እዩ።";
      case TR -> "Bir uzman artık danışmanlığınıza destek vermiyor.";
    };
  }

  String staffStatementSupervisorAdded(LanguageCode languageCode) {
    return switch (OrisoEmailRenderer.Tone.of(languageCode)) {
      case DE_FORMAL, DE_INFORMAL ->
          "Sie wurden als Supervisor-Berater:in zu diesem Vorgang hinzugefügt.";
      case EN -> "You were added as supervisor consultant to this case.";
      case FR -> "Vous avez été ajouté à ce dossier en tant que professionnel superviseur.";
      case RU -> "Вы добавлены к этому делу в качестве консультанта-супервизора.";
      case TI -> "ከም ተቆጻጻሪ ኣማኻሪ ናብዚ ጉዳይ ተወሲኽኩም ኣለኹም።";
      case TR -> "Bu vakaya süpervizör danışman olarak eklendiniz.";
    };
  }

  String staffStatementSupervisorRemoved(LanguageCode languageCode) {
    return switch (OrisoEmailRenderer.Tone.of(languageCode)) {
      case DE_FORMAL, DE_INFORMAL ->
          "Sie wurden als Supervisor-Berater:in aus diesem Vorgang entfernt.";
      case EN -> "You were removed as supervisor consultant from this case.";
      case FR -> "Vous avez été retiré de ce dossier en tant que professionnel superviseur.";
      case RU -> "Вы удалены из этого дела в качестве консультанта-супервизора.";
      case TI -> "ከም ተቆጻጻሪ ኣማኻሪ ካብዚ ጉዳይ ተኣሊኹም ኣለኹም።";
      case TR -> "Bu vakadan süpervizör danışman olarak çıkarıldınız.";
    };
  }
}
