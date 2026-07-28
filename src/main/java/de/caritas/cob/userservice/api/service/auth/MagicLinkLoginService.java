package de.caritas.cob.userservice.api.service.auth;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkLoginService {

  private static final Duration MAGIC_LINK_TOKEN_TTL = Duration.ofMinutes(15);
  private static final String TOKEN_SCOPE = "magic-login";

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull RestTemplate restTemplate;
  private final @NonNull IdentitySessionExchange identitySessionExchange;
  private final @NonNull OneTimeTokenStore oneTimeTokenStore;

  @Value("${identity.email-dummy-suffix:@beratungcaritas.de}")
  private String emailDummySuffix;

  @Value("${magic.link.frontend.base-url:https://app.oriso.org}")
  private String magicLinkFrontendBaseUrl;

  @Value("${consulting.type.service.api.url:}")
  private String consultingTypeServiceApiUrl;

  public MagicLinkRequestResult requestMagicLink(String usernameInput) {
    if (isBlank(usernameInput)) {
      return MagicLinkRequestResult.ACCEPTED;
    }

    Optional<AccountLoginTarget> accountOptional = resolveAccount(usernameInput.trim());
    if (accountOptional.isEmpty()) {
      return MagicLinkRequestResult.ACCEPTED;
    }

    AccountLoginTarget account = accountOptional.get();
    if (Boolean.FALSE.equals(account.getMagicLinkLoginEnabled())) {
      return MagicLinkRequestResult.NOT_ENABLED;
    }

    if (!isMagicLinkAllowedForAccount(account)) {
      return MagicLinkRequestResult.ACCEPTED;
    }

    sendMagicLinkEmailSafely(account);
    return MagicLinkRequestResult.ACCEPTED;
  }

  public Optional<IdentitySession> consumeMagicLink(String token) {
    if (isBlank(token)) {
      return Optional.empty();
    }

    Optional<OneTimeTokenStore.TokenClaim> claim;
    try {
      claim = oneTimeTokenStore.claim(TOKEN_SCOPE, token);
    } catch (RuntimeException redisFailure) {
      log.warn(
          "Magic-link token validation unavailable ({})", redisFailure.getClass().getSimpleName());
      return Optional.empty();
    }
    if (claim.isEmpty()) {
      return Optional.empty();
    }

    Optional<IdentitySession> exchanged;
    try {
      exchanged = identitySessionExchange.exchangeForUser(claim.get().subjectId());
    } catch (RuntimeException exchangeFailure) {
      log.warn(
          "Magic-link identity session exchange unavailable ({})",
          exchangeFailure.getClass().getSimpleName());
      restoreTokenForRetry(token, claim.get());
      return Optional.empty();
    }
    if (exchanged.isEmpty()) {
      // Restore token for short-lived retry if exchange failed due transient infra issue.
      restoreTokenForRetry(token, claim.get());
      return Optional.empty();
    }
    return exchanged;
  }

  private void restoreTokenForRetry(String token, OneTimeTokenStore.TokenClaim claim) {
    try {
      oneTimeTokenStore.restore(TOKEN_SCOPE, token, claim, false);
    } catch (RuntimeException redisFailure) {
      log.warn(
          "Magic-link token retry restoration unavailable ({})",
          redisFailure.getClass().getSimpleName());
    }
  }

  private Optional<AccountLoginTarget> resolveAccount(String username) {
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
      return Optional.of(
          new AccountLoginTarget(
              user.getUserId(),
              user.getUsername(),
              user.getEmail(),
              user.getMagicLinkLoginEnabled()));
    }

    Optional<Consultant> consultantOptional =
        consultantService.findConsultantByUsernameOrEmail(username, username);
    if (consultantOptional.isPresent()) {
      Consultant consultant = consultantOptional.get();
      return Optional.of(
          new AccountLoginTarget(
              consultant.getId(),
              consultant.getUsername(),
              consultant.getEmail(),
              consultant.getMagicLinkLoginEnabled()));
    }

    return Optional.empty();
  }

  private boolean isMagicLinkAllowedForAccount(AccountLoginTarget target) {
    return Boolean.TRUE.equals(target.getMagicLinkLoginEnabled())
        && isNotBlank(target.getEmail())
        && (emailDummySuffix == null || !target.getEmail().endsWith(emailDummySuffix))
        && resolveGlobalSmtpSettings().isPresent();
  }

  private void sendMagicLinkEmailSafely(AccountLoginTarget target) {
    try {
      var smtpSettingsOptional = resolveGlobalSmtpSettings();
      if (smtpSettingsOptional.isEmpty()) {
        return;
      }
      var smtpSettings = smtpSettingsOptional.get();
      String decodedUsername = new UsernameTranscoder().decodeUsername(target.getUsername());
      String oneTimeToken = generateAndStoreToken(target.getKeycloakUserId());
      String magicUrl = buildMagicFrontendUrl(oneTimeToken);

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
      message.setSubject("Your ORISO magic login link");
      message.setContent(
          buildHtml(magicUrl, smtpSettings.getEmailThemeColor()), "text/html; charset=UTF-8");
      Transport.send(message);
    } catch (Exception ex) {
      log.warn(
          "Magic link email dispatch failed for account {}, reason: {}",
          target.getUsername(),
          ex.getMessage());
    }
  }

  private String buildHtml(String magicUrl, String emailThemeColor) {
    String color =
        isNotBlank(emailThemeColor) && emailThemeColor.matches("^#([A-Fa-f0-9]{6})$")
            ? emailThemeColor
            : "#0f3b8f";
    String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f6f7fb;font-family:Arial,sans-serif;\">"
        + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"padding:24px 0;\">"
        + "<tr><td align=\"center\">"
        + "<table role=\"presentation\" width=\"620\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:620px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">"
        + "<tr><td style=\"background:"
        + color
        + ";padding:18px 24px;color:#ffffff;font-size:20px;font-weight:700;\">ORISO</td></tr>"
        + "<tr><td style=\"padding:28px 24px 8px 24px;color:#111827;font-size:24px;line-height:32px;font-weight:700;\">Login with magic link</td></tr>"
        + "<tr><td style=\"padding:0 24px 14px 24px;color:#374151;font-size:16px;line-height:24px;\">Use this one-time link to continue your ORISO login flow.</td></tr>"
        + "<tr><td style=\"padding:0 24px 18px 24px;\"><a href=\""
        + magicUrl
        + "\" style=\"display:inline-block;background:"
        + color
        + ";color:#ffffff;text-decoration:none;padding:12px 18px;border-radius:8px;font-weight:600;\">Open Magic Link</a></td></tr>"
        + "<tr><td style=\"padding:0 24px 24px 24px;color:#6b7280;font-size:14px;line-height:22px;\">This link expires in 15 minutes. Sent at: "
        + now
        + "</td></tr>"
        + "</table></td></tr></table></body></html>";
  }

  private String generateAndStoreToken(String keycloakUserId) {
    String token =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    oneTimeTokenStore.store(
        TOKEN_SCOPE, token, keycloakUserId, Instant.now().plus(MAGIC_LINK_TOKEN_TTL), false);
    return token;
  }

  private String buildMagicFrontendUrl(String oneTimeToken) {
    return normalizeBaseUrl(magicLinkFrontendBaseUrl)
        + "/login?magicToken="
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
      log.debug("Could not resolve global SMTP settings for magic link mail: {}", ex.getMessage());
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
  private static class AccountLoginTarget {
    String keycloakUserId;
    String username;
    String email;
    Boolean magicLinkLoginEnabled;
  }

  public enum MagicLinkRequestResult {
    ACCEPTED,
    NOT_ENABLED
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
}
