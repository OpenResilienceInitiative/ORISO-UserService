package de.caritas.cob.userservice.api.service.auth;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailMime;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkLoginService {

  private static final Duration MAGIC_LINK_TOKEN_TTL = Duration.ofMinutes(15);
  private static final String TOKEN_SCOPE = "magic-login";

  private final @NonNull UserService userService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull IdentitySessionExchange identitySessionExchange;
  private final @NonNull OneTimeTokenStore oneTimeTokenStore;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull PlatformSmtpSettingsProvider platformSmtpSettings;
  private MagicLinkMailSender mailSender = this::sendViaSmtp;

  @Value("${identity.email-dummy-suffix:@beratungcaritas.de}")
  private String emailDummySuffix;

  @Value("${magic.link.frontend.base-url}")
  private String magicLinkFrontendBaseUrl;

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
        && (emailDummySuffix == null || !target.getEmail().endsWith(emailDummySuffix));
  }

  private void sendMagicLinkEmailSafely(AccountLoginTarget target) {
    try {
      var smtpSettingsOptional = resolvePlatformSmtpSettings();
      if (smtpSettingsOptional.isEmpty()) {
        return;
      }
      var smtpSettings = smtpSettingsOptional.get();
      String oneTimeToken = generateAndStoreToken(target.getKeycloakUserId());
      String magicUrl = buildMagicFrontendUrl(oneTimeToken);
      mailSender.send(target.getEmail(), magicUrl, smtpSettings);
    } catch (Exception ex) {
      log.warn("Magic link email dispatch failed ({})", ex.getClass().getSimpleName());
    }
  }

  private void sendViaSmtp(
      String recipient, String magicUrl, PlatformSmtpSettingsProvider.Settings smtpSettings)
      throws Exception {
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.host", smtpSettings.host());
    props.put("mail.smtp.port", String.valueOf(smtpSettings.port()));
    if (smtpSettings.secure()) {
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
                return new PasswordAuthentication(smtpSettings.username(), smtpSettings.password());
              }
            });

    var email = renderMagicLink(magicUrl, null);
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(smtpSettings.from()));
    message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
    message.setSubject(email.subject(), "UTF-8");
    message.setContent(OrisoEmailMime.alternative(email));
    Transport.send(message);
  }

  @FunctionalInterface
  interface MagicLinkMailSender {
    void send(String recipient, String magicUrl, PlatformSmtpSettingsProvider.Settings smtpSettings)
        throws Exception;
  }

  /**
   * Renders the sign-in link from the design system.
   *
   * <p>Replaces the 620px Arial card this class used to concatenate inline, which sat on {@code
   * #f6f7fb} with an {@code #e5e7eb} border while the design system specifies a 600px card on
   * {@code #f2efef} with {@code #e0dada} — three values that made every ORISO mail look like it
   * came from a different sender.
   *
   * <p>The mail is German now. The old subject read "Your ORISO magic login link" while the rest of
   * the platform addresses German-speaking users, which was an accident of the inline copy rather
   * than a decision.
   */
  private OrisoEmailRenderer.RenderedEmail renderMagicLink(
      String magicUrl, String emailThemeColor) {
    Map<String, String> values =
        new LinkedHashMap<>(emailBrand.values(magicLinkFrontendBaseUrl, emailThemeColor));
    values.put("loginUrl", magicUrl);
    values.put("expiryMinutes", String.valueOf(MAGIC_LINK_TOKEN_TTL.toMinutes()));
    return emailRenderer.render("anmeldelink", OrisoEmailRenderer.Tone.DE_FORMAL, values);
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

  private Optional<PlatformSmtpSettingsProvider.Settings> resolvePlatformSmtpSettings() {
    try {
      return Optional.of(platformSmtpSettings.requireConfigured());
    } catch (IllegalStateException exception) {
      log.warn("Sign-in link email not sent: {}", exception.getMessage());
      return Optional.empty();
    }
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
}
