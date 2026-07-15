package de.caritas.cob.userservice.api.service.auth;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
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
import java.util.concurrent.ConcurrentHashMap;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

  private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(120);

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull KeycloakService keycloakService;
  private final @NonNull RestTemplate restTemplate;

  private final Map<String, ResetTokenEntry> resetTokens = new ConcurrentHashMap<>();

  @Value("${identity.email-dummy-suffix:@beratungcaritas.de}")
  private String emailDummySuffix;

  @Value("${password.reset.frontend.base-url:https://app.oriso.org}")
  private String passwordResetFrontendBaseUrl;

  @Value("${consulting.type.service.api.url:}")
  private String consultingTypeServiceApiUrl;

  /**
   * Always completes without signalling whether the account exists, to avoid account enumeration.
   * If an eligible account is found, a reset email is sent best-effort.
   */
  public void requestPasswordReset(String usernameInput, String locale) {
    if (isBlank(usernameInput)) {
      return;
    }

    Optional<AccountResetTarget> accountOptional = resolveAccount(usernameInput.trim());
    if (accountOptional.isEmpty()) {
      return;
    }

    AccountResetTarget account = accountOptional.get();
    if (isBlank(account.getEmail())
        || (emailDummySuffix != null && account.getEmail().endsWith(emailDummySuffix))) {
      return;
    }

    sendPasswordResetEmailSafely(account, resolveLocale(locale));
  }

  /**
   * Validates the one-time token and, if valid, sets the new password in Keycloak.
   *
   * @return true if the password was updated, false if the token is missing/invalid/expired
   */
  public boolean confirmPasswordReset(String token, String newPassword) {
    if (isBlank(token) || isBlank(newPassword)) {
      return false;
    }

    cleanupExpiredTokens();
    ResetTokenEntry entry = resetTokens.remove(token);
    if (entry == null || entry.getExpiresAt().isBefore(Instant.now())) {
      return false;
    }

    keycloakService.updatePassword(entry.getKeycloakUserId(), newPassword);
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

  private String resolveLocale(String locale) {
    return isNotBlank(locale) && EMAIL_CONTENT.containsKey(locale) ? locale : "de";
  }

  private void sendPasswordResetEmailSafely(AccountResetTarget target, String locale) {
    try {
      var smtpSettingsOptional = resolveGlobalSmtpSettings();
      if (smtpSettingsOptional.isEmpty()) {
        return;
      }
      var smtpSettings = smtpSettingsOptional.get();
      String oneTimeToken = generateAndStoreToken(target.getKeycloakUserId());
      String resetUrl = buildResetFrontendUrl(oneTimeToken);
      EmailContent content = EMAIL_CONTENT.get(locale);

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
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(target.getEmail()));
      message.setSubject(content.getSubject());
      message.setContent(
          buildHtml(content, resetUrl, smtpSettings.getEmailThemeColor()),
          "text/html; charset=UTF-8");
      Transport.send(message);
    } catch (Exception ex) {
      log.warn(
          "Password reset email dispatch failed for account {}, reason: {}",
          target.getKeycloakUserId(),
          ex.getMessage());
    }
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
    resetTokens.put(
        token, new ResetTokenEntry(keycloakUserId, Instant.now().plus(RESET_TOKEN_TTL)));
    return token;
  }

  private void cleanupExpiredTokens() {
    Instant now = Instant.now();
    resetTokens.entrySet().removeIf(entry -> entry.getValue().getExpiresAt().isBefore(now));
  }

  private String buildResetFrontendUrl(String oneTimeToken) {
    return normalizeBaseUrl(passwordResetFrontendBaseUrl)
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
  private static class ResetTokenEntry {
    String keycloakUserId;
    Instant expiresAt;
  }

  @lombok.Value
  private static class GlobalSmtpSettings {
    String host;
    Integer port;
    boolean secure;
    String username;
    String password;
    String from;
    String emailThemeColor;
  }

  @lombok.Value
  private static class EmailContent {
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
