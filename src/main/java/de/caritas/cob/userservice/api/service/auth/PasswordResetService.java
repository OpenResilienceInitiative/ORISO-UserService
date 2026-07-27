package de.caritas.cob.userservice.api.service.auth;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetApplication;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Self-service password reset, mirroring {@link MagicLinkLoginService}: generates our own one-time
 * token and sends our own branded, localized email directly via SMTP, instead of handing off to
 * Keycloak's hosted reset-credentials pages/theme (ORISO-Helm#72).
 *
 * <p>Reset tokens live in the shared {@link OneTimeTokenStore}. A request and confirmation may be
 * handled by different replicas, and replacing an application instance does not invalidate an
 * outstanding link.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

  private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(120);
  private static final String TOKEN_SCOPE = "password-reset";

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull IdentityPasswordUpdater identityPasswordUpdater;
  private final @NonNull RestTemplate restTemplate;
  private final @NonNull OneTimeTokenStore oneTimeTokenStore;

  /**
   * Runs the (potentially slow) account lookup + mail dispatch off the request thread so the HTTP
   * response returns in near-constant time regardless of whether the account exists — mitigates
   * timing-based account enumeration. Overridable in tests with a synchronous executor.
   */
  private final ThreadPoolExecutor defaultPasswordResetExecutor =
      new ThreadPoolExecutor(
          2,
          4,
          30L,
          TimeUnit.SECONDS,
          // Bounded queue: this is a public, unauthenticated endpoint — an unbounded queue
          // would let request floods accumulate work in memory. Overflow is rejected (and
          // swallowed by the caller) so the HTTP response stays identical either way.
          new ArrayBlockingQueue<>(200),
          runnable -> {
            Thread thread = new Thread(runnable, "password-reset-dispatch");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private Executor passwordResetExecutor = defaultPasswordResetExecutor;

  @PreDestroy
  void shutdownPasswordResetExecutor() {
    defaultPasswordResetExecutor.shutdown();
  }

  /** Seam for sending the reset mail; the default delivers via SMTP. Overridable in tests. */
  private PasswordResetMailSender mailSender = this::sendViaSmtp;

  @Value("${identity.email-dummy-suffix:@beratungcaritas.de}")
  private String emailDummySuffix;

  @Value("${password.reset.frontend.base-url:}")
  private String passwordResetFrontendBaseUrl;

  @Value("${password.reset.admin.frontend.base-url:}")
  private String passwordResetAdminFrontendBaseUrl;

  @Value("${consulting.type.service.api.url:}")
  private String consultingTypeServiceApiUrl;

  @PostConstruct
  void logFeatureAvailability() {
    if (isBlank(passwordResetFrontendBaseUrl)) {
      log.warn(
          "Self-service password reset is DISABLED: required property "
              + "'password.reset.frontend.base-url' (env PASSWORD_RESET_FRONTEND_BASE_URL) is unset. "
              + "Reset emails will not be sent until it is configured.");
    }
    if (isBlank(passwordResetAdminFrontendBaseUrl)) {
      log.warn(
          "Admin password reset is DISABLED: required property "
              + "'password.reset.admin.frontend.base-url' "
              + "(env PASSWORD_RESET_ADMIN_FRONTEND_BASE_URL) is unset. "
              + "Admin reset emails will not be sent until it is configured.");
    }
  }

  /**
   * Always completes without signalling whether the account exists, to avoid account enumeration.
   * If an eligible account is found, a reset email is sent best-effort.
   */
  public void requestPasswordReset(String usernameInput, String locale) {
    requestPasswordReset(usernameInput, locale, PasswordResetApplication.APP);
  }

  public void requestPasswordReset(
      String usernameInput, String locale, PasswordResetApplication application) {
    if (isBlank(usernameInput)) {
      return;
    }

    String username = usernameInput.trim();
    String resolvedLocale = resolveLocale(locale);
    PasswordResetApplication resolvedApplication =
        application == null ? PasswordResetApplication.APP : application;
    try {
      // Dispatch asynchronously so the response time does not reveal whether the account exists.
      passwordResetExecutor.execute(
          () -> processPasswordResetRequest(username, resolvedLocale, resolvedApplication));
    } catch (RejectedExecutionException ex) {
      // Queue saturated (flood/overload): drop the dispatch, keep the response identical so
      // neither existence nor the drop is observable. No PII in the log.
      log.warn("Password reset dispatch rejected — executor queue saturated");
    }
  }

  private void processPasswordResetRequest(
      String username, String locale, PasswordResetApplication application) {
    try {
      Optional<AccountResetTarget> accountOptional = resolveAccount(username, application);
      if (accountOptional.isEmpty()) {
        return;
      }

      AccountResetTarget account = accountOptional.get();
      if (isBlank(account.getEmail())
          || (emailDummySuffix != null && account.getEmail().endsWith(emailDummySuffix))) {
        return;
      }

      sendPasswordResetEmailSafely(account, locale, application);
    } catch (RuntimeException ex) {
      // Never leak PII (account id, email, raw message) — log the exception class only.
      log.warn("Password reset request processing failed ({})", ex.getClass().getSimpleName());
      log.debug("Password reset request processing failure detail", ex);
    }
  }

  /**
   * Validates the one-time token and, if valid, sets the new password in Keycloak.
   *
   * <p>The token is only consumed once {@link IdentityPasswordUpdater#updatePassword} succeeds — if
   * Keycloak rejects the password (e.g. policy violation), the token stays valid so the user can
   * retry with a different password using the same emailed link, mirroring how {@link
   * MagicLinkLoginService} restores its token on a failed exchange.
   *
   * @return true if the password was updated, false if the token is missing/invalid/expired
   */
  public boolean confirmPasswordReset(String token, String newPassword) {
    if (isBlank(token) || isBlank(newPassword)) {
      return false;
    }

    Optional<OneTimeTokenStore.TokenClaim> claim;
    try {
      // Claim the token atomically (remove-first) so concurrent confirmations cannot both succeed.
      claim = oneTimeTokenStore.claim(TOKEN_SCOPE, token);
    } catch (RuntimeException redisFailure) {
      log.warn(
          "Password-reset token validation unavailable ({})",
          redisFailure.getClass().getSimpleName());
      return false;
    }
    if (claim.isEmpty()) {
      return false;
    }

    try {
      identityPasswordUpdater.updatePassword(claim.get().subjectId(), newPassword);
    } catch (CustomValidationHttpStatusException ex) {
      // Definitive password-policy rejection: Keycloak did NOT apply the password, so the token
      // can safely be restored for a retry with a different password via the same emailed link
      // (mirrors MagicLinkLoginService). Note: there is a small window between the remove above
      // and this re-insert during which a concurrent confirm would observe the token as absent —
      // an acceptable trade for guaranteed single-use on success.
      try {
        oneTimeTokenStore.restore(TOKEN_SCOPE, token, claim.get(), true);
      } catch (RuntimeException redisFailure) {
        log.warn(
            "Password-reset token retry restoration unavailable ({})",
            redisFailure.getClass().getSimpleName());
      }
      throw ex;
    }
    // Any other failure: the update outcome is unknown (Keycloak may have applied the password
    // before the error surfaced), so the token stays consumed — re-inserting could allow a second
    // password change with an already-used link.
    return true;
  }

  private Optional<AccountResetTarget> resolveAccount(String username) {
    var transcoder = new UsernameTranscoder();
    String decoded = transcoder.decodeUsername(username);
    String encoded = transcoder.encodeUsername(username);

    Optional<User> userOptional = userService.findUserByUsername(username);
    if (userOptional.isEmpty() && !decoded.equals(username)) {
      userOptional = userService.findUserByUsername(decoded);
    }
    if (userOptional.isEmpty() && !encoded.equals(username)) {
      userOptional = userService.findUserByUsername(encoded);
    }
    if (userOptional.isPresent()) {
      User user = userOptional.get();
      return Optional.of(new AccountResetTarget(user.getUserId(), user.getEmail()));
    }

    Optional<Consultant> consultantOptional =
        consultantService.findConsultantByUsernameOrEmail(username, username);
    return consultantOptional.map(
        consultant -> new AccountResetTarget(consultant.getId(), consultant.getEmail()));
  }

  private Optional<AccountResetTarget> resolveAccount(
      String username, PasswordResetApplication application) {
    if (application == PasswordResetApplication.ADMIN) {
      Optional<Admin> adminOptional =
          adminRepository.findFirstByUsernameIgnoreCaseOrEmailIgnoreCase(username, username);
      return adminOptional.map(admin -> new AccountResetTarget(admin.getId(), admin.getEmail()));
    }
    return resolveAccount(username);
  }

  private String resolveLocale(String locale) {
    return isNotBlank(locale) && EMAIL_CONTENT.containsKey(locale) ? locale : "de";
  }

  private void sendPasswordResetEmailSafely(
      AccountResetTarget target, String locale, PasswordResetApplication application) {
    String frontendBaseUrl =
        application == PasswordResetApplication.ADMIN
            ? passwordResetAdminFrontendBaseUrl
            : passwordResetFrontendBaseUrl;
    String frontendBaseUrlProperty =
        application == PasswordResetApplication.ADMIN
            ? "password.reset.admin.frontend.base-url"
            : "password.reset.frontend.base-url";
    // Fail closed: without an explicitly configured frontend base URL we cannot build a usable
    // reset link, so the feature stays disabled instead of emailing a wrong/production URL.
    if (isBlank(frontendBaseUrl)) {
      log.warn("Password reset email not sent: '{}' is not configured.", frontendBaseUrlProperty);
      return;
    }

    var smtpSettingsOptional = resolveGlobalSmtpSettings();
    if (smtpSettingsOptional.isEmpty()) {
      return;
    }
    var smtpSettings = smtpSettingsOptional.get();

    String oneTimeToken = generateAndStoreToken(target.getKeycloakUserId());
    String resetUrl = buildResetFrontendUrl(oneTimeToken, frontendBaseUrl);
    EmailContent content = EMAIL_CONTENT.get(locale);

    try {
      mailSender.send(target.getEmail(), locale, resetUrl, smtpSettings, content);
    } catch (Exception ex) {
      // Do not leave a token behind for a mail that never went out, and never log PII (account id,
      // recipient, or the raw exception message) — record the exception class only.
      try {
        oneTimeTokenStore.discard(TOKEN_SCOPE, oneTimeToken, target.getKeycloakUserId());
      } catch (RuntimeException redisFailure) {
        log.warn(
            "Password-reset token cleanup unavailable ({})",
            redisFailure.getClass().getSimpleName());
      }
      log.warn("Password reset email dispatch failed ({})", ex.getClass().getSimpleName());
      log.debug("Password reset email dispatch failure detail", ex);
    }
  }

  private void sendViaSmtp(
      String recipient,
      String locale,
      String resetUrl,
      GlobalSmtpSettings smtpSettings,
      EmailContent content)
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

    Message message = new MimeMessage(session);
    message.setFrom(new InternetAddress(smtpSettings.getFrom()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
    message.setSubject(content.getSubject());
    message.setContent(
        buildHtml(content, resetUrl, smtpSettings.getEmailThemeColor()),
        "text/html; charset=UTF-8");
    Transport.send(message);
  }

  /**
   * Seam so tests can capture the recipient, locale and reset URL without opening an SMTP socket.
   */
  @FunctionalInterface
  interface PasswordResetMailSender {
    void send(
        String recipient,
        String locale,
        String resetUrl,
        GlobalSmtpSettings smtpSettings,
        EmailContent content)
        throws Exception;
  }

  private String buildHtml(EmailContent content, String resetUrl, String emailThemeColor) {
    String color =
        isNotBlank(emailThemeColor) && emailThemeColor.matches("^#([A-Fa-f0-9]{6})$")
            ? emailThemeColor
            : "#d80003";

    return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f6f7fb;font-family:Arial,sans-serif;\">"
        + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:24px 0;\">"
        + "<tr><td align=\"center\">"
        + "<table role=\"presentation\" width=\"620\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:620px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">"
        + "<tr><td style=\"background:"
        + color
        + ";padding:18px 24px;color:#ffffff;font-size:20px;font-weight:700;\">Caritas Online-Beratung</td></tr>"
        + "<tr><td style=\"padding:28px 24px 8px 24px;color:#111827;font-size:22px;line-height:30px;font-weight:700;\">"
        + content.getSubject()
        + "</td></tr>"
        + "<tr><td style=\"padding:0 24px 14px 24px;color:#374151;font-size:16px;line-height:24px;\">"
        + content.getIntro()
        + "</td></tr>"
        + "<tr><td style=\"padding:0 24px 18px 24px;\"><a href=\""
        + resetUrl
        + "\" style=\"display:inline-block;background:"
        + color
        + ";color:#ffffff;text-decoration:none;padding:12px 18px;border-radius:8px;font-weight:600;\">"
        + content.getLinkLabel()
        + "</a></td></tr>"
        + "<tr><td style=\"padding:0 24px 24px 24px;color:#6b7280;font-size:14px;line-height:22px;\">"
        + content.getExpiry()
        + "</td></tr>"
        + "</table></td></tr></table></body></html>";
  }

  private String generateAndStoreToken(String keycloakUserId) {
    String token =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    // Cap at one outstanding token per account across all replicas.
    oneTimeTokenStore.store(
        TOKEN_SCOPE, token, keycloakUserId, Instant.now().plus(RESET_TOKEN_TTL), true);
    return token;
  }

  private String buildResetFrontendUrl(String oneTimeToken, String frontendBaseUrl) {
    return normalizeBaseUrl(frontendBaseUrl)
        + "/password-reset/confirm?token="
        + URLEncoder.encode(oneTimeToken, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private Optional<GlobalSmtpSettings> resolveGlobalSmtpSettings() {
    if (isBlank(consultingTypeServiceApiUrl)) {
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
      String username = asStringSettingValue(settingsResponse.get("globalSmtpUsername"));
      String password = asStringSettingValue(settingsResponse.get("globalSmtpPassword"));
      String from = asStringSettingValue(settingsResponse.get("globalSmtpFrom"));
      String emailThemeColor =
          asStringSettingValue(settingsResponse.get("globalSmtpEmailThemeColor"));

      if (!systemEmailsEnabled
          || !smtpEnabled
          || isBlank(host)
          || port == null
          || isBlank(username)
          || isBlank(password)
          || isBlank(from)) {
        return Optional.empty();
      }

      return Optional.of(
          new GlobalSmtpSettings(host, port, secure, username, password, from, emailThemeColor));
    } catch (Exception ex) {
      log.debug(
          "Could not resolve global SMTP settings for password reset mail: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  private boolean asBooleanSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return "true".equalsIgnoreCase((String) value);
    }
    return false;
  }

  private String asStringSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    return nonNull(value) ? String.valueOf(value).trim() : null;
  }

  private Integer asIntSettingValue(Object raw) {
    Object value = unwrapSettingValue(raw);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String && isNotBlank((String) value)) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Object unwrapSettingValue(Object raw) {
    if (raw instanceof Map<?, ?>) {
      return ((Map<String, Object>) raw).get("value");
    }
    return raw;
  }

  private String normalizeBaseUrl(String value) {
    if (isBlank(value)) {
      return "";
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  @lombok.Value
  private static class AccountResetTarget {
    String keycloakUserId;
    String email;
  }

  @lombok.Value
  static class GlobalSmtpSettings {
    String host;
    Integer port;
    boolean secure;
    String username;
    String password;
    String from;
    String emailThemeColor;
  }

  @lombok.Value
  static class EmailContent {
    String subject;
    String intro;
    String linkLabel;
    String expiry;
  }

  private static final Map<String, EmailContent> EMAIL_CONTENT =
      Map.of(
          "de",
          new EmailContent(
              "Passwort zurücksetzen",
              "Es wurde eine Änderung der Anmeldeinformationen für Ihren Account Caritas Onlineberatung angefordert. Wenn Sie diese Änderung beantragt haben, klicken Sie auf den unten stehenden Link.",
              "Link zum Zurücksetzen von Anmeldeinformationen",
              "Die Gültigkeit des Links wird in 120 Minuten verfallen. Sollten Sie keine Änderung vollziehen wollen, können Sie diese Nachricht ignorieren."),
          "en",
          new EmailContent(
              "Reset password",
              "A change of login credentials for your Caritas Online Counselling account has been requested. If you requested this change, click the link below.",
              "Link to reset login credentials",
              "This link will expire in 120 minutes. If you do not want to make this change, you can ignore this message."),
          "fr",
          new EmailContent(
              "Réinitialiser le mot de passe",
              "Une modification des informations de connexion de votre compte Caritas Online-Beratung a été demandée. Si vous êtes à l'origine de cette demande, cliquez sur le lien ci-dessous.",
              "Lien pour réinitialiser les informations de connexion",
              "Ce lien expirera dans 120 minutes. Si vous ne souhaitez pas effectuer cette modification, vous pouvez ignorer ce message."),
          "ru",
          new EmailContent(
              "Сброс пароля",
              "Был запрошен сброс учётных данных для входа в ваш аккаунт Caritas Online-Beratung. Если вы запросили это изменение, перейдите по ссылке ниже.",
              "Ссылка для сброса учётных данных",
              "Срок действия ссылки истекает через 120 минут. Если вы не хотите вносить это изменение, просто проигнорируйте это письмо."),
          "ti",
          new EmailContent(
              "ፓስዎርድ ዳግማይ ምቕማጥ",
              "ንኣካውንትካ Caritas Online-Beratung ናይ መእተዊ ሓበሬታ ንምቕያር ተሓቲቱ ኣሎ። እዚ ለውጢ እዚ ንስኻ እንተኾንካ ሓቲትካዮ፣ ነቲ ኣብ ታሕቲ ዘሎ ሊንክ ጠውቕ።",
              "ናይ መእተዊ ሓበሬታ ዳግማይ ንምቕማጥ ዝኸውን ሊንክ",
              "ጽንዓት እዚ ሊንክ እዚ ኣብ 120 ደቓይቕ ክውዳእ እዩ። እዚ ለውጢ እዚ ክትገብር ዘይትደሊ እንተኾንካ፣ ነዚ መልእኽቲ እዚ ክትንዕቆ ትኽእል ኢኻ።"),
          "tr",
          new EmailContent(
              "Şifreyi sıfırla",
              "Caritas Online-Beratung hesabınız için oturum açma bilgilerinizde bir değişiklik talep edildi. Bu değişikliği siz talep ettiyseniz aşağıdaki bağlantıya tıklayın.",
              "Oturum açma bilgilerini sıfırlama bağlantısı",
              "Bu bağlantının geçerlilik süresi 120 dakika içinde dolacaktır. Bu değişikliği yapmak istemiyorsanız bu mesajı yok sayabilirsiniz."));
}
