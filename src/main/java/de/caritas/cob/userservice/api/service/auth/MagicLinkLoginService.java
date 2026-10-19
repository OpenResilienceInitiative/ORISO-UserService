package de.caritas.cob.userservice.api.service.auth;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailMime;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.OrisoSmtpTransport;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
  private final @NonNull ApplicationSettingsService applicationSettingsService;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;

  @Value("${identity.email-dummy-suffix:@beratungcaritas.de}")
  private String emailDummySuffix;

  @Value("${magic.link.frontend.base-url}")
  private String magicLinkFrontendBaseUrl;

  public MagicLinkRequestResult requestMagicLink(String usernameInput) {
    if (isBlank(usernameInput)) {
      return MagicLinkRequestResult.ACCEPTED;
    }

    // Public route without a tenant; usernames are unique across Träger.
    Optional<AccountLoginTarget> accountOptional =
        TenantContext.supplyAcrossTenants(() -> resolveAccount(usernameInput.trim()));
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

    var smtpSettings = resolveGlobalSmtpSettings();
    smtpSettings.ifPresent(settings -> sendMagicLinkEmailSafely(account, settings));
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
              user.getMagicLinkLoginEnabled(),
              user.getTenantId(),
              user.getLanguageCode()));
    }

    Optional<Consultant> consultantOptional = consultantService.findConsultantForSignIn(username);
    if (consultantOptional.isPresent()) {
      Consultant consultant = consultantOptional.get();
      return Optional.of(
          new AccountLoginTarget(
              consultant.getId(),
              consultant.getUsername(),
              consultant.getEmail(),
              consultant.getMagicLinkLoginEnabled(),
              consultant.getTenantId(),
              consultant.getLanguageCode()));
    }

    return Optional.empty();
  }

  private boolean isMagicLinkAllowedForAccount(AccountLoginTarget target) {
    return Boolean.TRUE.equals(target.getMagicLinkLoginEnabled())
        && isNotBlank(target.getEmail())
        && (emailDummySuffix == null || !target.getEmail().endsWith(emailDummySuffix));
  }

  private void sendMagicLinkEmailSafely(
      AccountLoginTarget target, GlobalSmtpSettings smtpSettings) {
    try {
      String decodedUsername = new UsernameTranscoder().decodeUsername(target.getUsername());
      String oneTimeToken = generateToken();
      String magicUrl = buildMagicFrontendUrl(oneTimeToken);

      jakarta.mail.Session session =
          OrisoSmtpTransport.session(
              smtpSettings.getHost(),
              smtpSettings.getPort(),
              smtpSettings.isSecure(),
              smtpSettings.getUsername(),
              smtpSettings.getPassword());

      var email = renderMagicLink(magicUrl, target.getTenantId(), target.getLanguageCode());
      oneTimeTokenStore.store(
          TOKEN_SCOPE,
          oneTimeToken,
          target.getKeycloakUserId(),
          Instant.now().plus(MAGIC_LINK_TOKEN_TTL),
          false);
      MimeMessage message = new MimeMessage(session);
      message.setFrom(new InternetAddress(smtpSettings.getFrom()));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(target.getEmail()));
      message.setSubject(email.subject(), "UTF-8");
      message.setContent(OrisoEmailMime.alternative(email));
      OrisoSmtpTransport.send(message);
    } catch (Exception ex) {
      log.warn("Magic link email dispatch failed ({})", ex.getClass().getSimpleName());
    }
  }

  /**
   * Renders the sign-in link from the design system.
   *
   * <p>Replaces the 620px Arial card this class used to concatenate inline, which sat on {@code
   * #f6f7fb} with an {@code #e5e7eb} border while the design system specifies a 600px card on
   * {@code #f2efef} with {@code #e0dada} — three values that made every ORISO mail look like it
   * came from a different sender.
   *
   * <p>The sign-in mail follows the recipient's stored language. A missing or unsupported language
   * fails before a token is issued, so no one receives a sign-in link in the wrong language.
   */
  private OrisoEmailRenderer.RenderedEmail renderMagicLink(
      String magicUrl, Long tenantId, LanguageCode languageCode) {
    var tone = OrisoEmailRenderer.Tone.of(languageCode);
    Map<String, String> values =
        new LinkedHashMap<>(emailBrand.valuesForTenant(magicLinkFrontendBaseUrl, tenantId));
    values.put("loginUrl", magicUrl);
    values.put("expiryMinutes", String.valueOf(MAGIC_LINK_TOKEN_TTL.toMinutes()));
    return emailRenderer.render("anmeldelink", tone, values);
  }

  private String generateToken() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }

  private String buildMagicFrontendUrl(String oneTimeToken) {
    return normalizeBaseUrl(magicLinkFrontendBaseUrl)
        + "/login?magicToken="
        + URLEncoder.encode(oneTimeToken, StandardCharsets.UTF_8);
  }

  private Optional<GlobalSmtpSettings> resolveGlobalSmtpSettings() {
    try {
      var settings = PlatformSmtpSettingsProvider.requireConfigured(applicationSettingsService);
      return Optional.of(
          new GlobalSmtpSettings(
              settings.host(),
              settings.port(),
              settings.secure(),
              settings.username(),
              settings.password(),
              settings.from(),
              settings.emailThemeColor()));
    } catch (IllegalStateException exception) {
      log.warn(
          "Platform SMTP unavailable for magic link mail ({})",
          exception.getClass().getSimpleName());
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
    Long tenantId;
    LanguageCode languageCode;
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
