package de.caritas.cob.userservice.api.service.auth;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkLoginService {

  private static final String TOKEN_ENDPOINT_PATH = "/token";
  private static final String TOKEN_GRANT_PASSWORD = "password";
  private static final String TOKEN_GRANT_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
  private static final Duration MAGIC_LINK_TOKEN_TTL = Duration.ofMinutes(15);

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull RestTemplate restTemplate;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final Map<String, MagicLoginTokenEntry> magicLoginTokens = new ConcurrentHashMap<>();

  @Value("${identity.email-dummy-suffix:@beratungcaritas.de}")
  private String emailDummySuffix;

  @Value("${magic.link.frontend.base-url:https://app.oriso-dev.site}")
  private String magicLinkFrontendBaseUrl;

  @Value("${keycloak.config.admin-username}")
  private String keycloakAdminUsername;

  @Value("${keycloak.config.admin-password}")
  private String keycloakAdminPassword;

  @Value("${keycloak.config.app-client-id:app}")
  private String keycloakAppClientId;

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

  public Optional<KeycloakLoginResponseDTO> consumeMagicLink(String token) {
    if (isBlank(token)) {
      return Optional.empty();
    }

    cleanupExpiredTokens();
    MagicLoginTokenEntry entry = magicLoginTokens.remove(token);
    if (entry == null || entry.getExpiresAt().isBefore(Instant.now())) {
      return Optional.empty();
    }

    KeycloakLoginResponseDTO exchanged = exchangeTokenForUser(entry.getKeycloakUserId());
    if (exchanged == null) {
      // Restore token for short-lived retry if exchange failed due transient infra issue.
      magicLoginTokens.put(token, entry);
      return Optional.empty();
    }
    return Optional.of(exchanged);
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
              user.getUserId(), user.getUsername(), user.getEmail(), user.getMagicLinkLoginEnabled()));
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
        && !target.getEmail().endsWith(emailDummySuffix)
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
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(target.getEmail()));
      message.setSubject("Your ORISO magic login link");
      message.setContent(buildHtml(magicUrl, smtpSettings.getEmailThemeColor()), "text/html; charset=UTF-8");
      Transport.send(message);
    } catch (Exception ex) {
      log.warn("Magic link email dispatch failed for account {}, reason: {}", target.getUsername(), ex.getMessage());
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
    String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    magicLoginTokens.put(token, new MagicLoginTokenEntry(keycloakUserId, Instant.now().plus(MAGIC_LINK_TOKEN_TTL)));
    return token;
  }

  private void cleanupExpiredTokens() {
    Instant now = Instant.now();
    magicLoginTokens.entrySet().removeIf(entry -> entry.getValue().getExpiresAt().isBefore(now));
  }

  private String buildMagicFrontendUrl(String oneTimeToken) {
    return normalizeBaseUrl(magicLinkFrontendBaseUrl)
        + "/login?magicToken="
        + URLEncoder.encode(oneTimeToken, StandardCharsets.UTF_8);
  }

  private KeycloakLoginResponseDTO exchangeTokenForUser(String keycloakUserId) {
    String adminToken = loginAdminForToken();
    if (isBlank(adminToken)) {
      return null;
    }
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", TOKEN_GRANT_EXCHANGE);
      form.add("client_id", keycloakAppClientId);
      form.add("subject_token", adminToken);
      form.add("requested_subject", keycloakUserId);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      return restTemplate.postForEntity(tokenUrl, entity, KeycloakLoginResponseDTO.class).getBody();
    } catch (Exception ex) {
      log.warn("Magic link token exchange failed for user {}, reason: {}", keycloakUserId, ex.getMessage());
      return null;
    }
  }

  private String loginAdminForToken() {
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", TOKEN_GRANT_PASSWORD);
      form.add("client_id", keycloakAppClientId);
      form.add("username", keycloakAdminUsername);
      form.add("password", keycloakAdminPassword);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
      String tokenUrl = identityClientConfig.getOpenIdConnectUrl(TOKEN_ENDPOINT_PATH);
      var response = restTemplate.postForEntity(tokenUrl, entity, Map.class);
      if (response.getBody() == null) {
        return null;
      }
      Object token = response.getBody().get("access_token");
      return token == null ? null : String.valueOf(token);
    } catch (Exception ex) {
      log.warn("Magic link admin token fetch failed, reason: {}", ex.getMessage());
      return null;
    }
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
          asBooleanSettingValue(settingsResponse.get("globalFeatureSystemNotificationEmailsEnabled"));
      boolean smtpEnabled = asBooleanSettingValue(settingsResponse.get("globalSmtpEnabled"));
      String host = asStringSettingValue(settingsResponse.get("globalSmtpHost"));
      Integer port = asIntSettingValue(settingsResponse.get("globalSmtpPort"));
      boolean secure = asBooleanSettingValue(settingsResponse.get("globalSmtpSecure"));
      String username = asStringSettingValue(settingsResponse.get("globalSmtpUsername"));
      String password = asStringSettingValue(settingsResponse.get("globalSmtpPassword"));
      String from = asStringSettingValue(settingsResponse.get("globalSmtpFrom"));
      String emailThemeColor = asStringSettingValue(settingsResponse.get("globalSmtpEmailThemeColor"));

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

  @lombok.Value
  private static class MagicLoginTokenEntry {
    String keycloakUserId;
    Instant expiresAt;
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

