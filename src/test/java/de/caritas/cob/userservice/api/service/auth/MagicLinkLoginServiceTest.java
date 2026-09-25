package de.caritas.cob.userservice.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.port.out.IdentitySessionExchange;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.MagicLinkMailSender;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.MagicLinkRequestResult;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MagicLinkLoginServiceTest {

  @Mock private UserService userService;
  @Mock private ConsultantService consultantService;
  @Mock private IdentitySessionExchange identitySessionExchange;
  @Mock private OneTimeTokenStore oneTimeTokenStore;
  @Mock private OrisoEmailRenderer emailRenderer;
  @Mock private OrisoEmailBrand emailBrand;
  @Mock private PlatformSmtpSettingsProvider platformSmtpSettings;

  @InjectMocks private MagicLinkLoginService magicLinkLoginService;
  private final List<SentMail> sentMails = new ArrayList<>();

  private record SentMail(String recipient, String url, String host, String from) {}

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(magicLinkLoginService, "emailDummySuffix", "@beratungcaritas.de");
    when(platformSmtpSettings.requireConfigured())
        .thenReturn(
            new PlatformSmtpSettingsProvider.Settings(
                "deployment-smtp.example.org",
                587,
                false,
                "deployment-user",
                "deployment-pass",
                "noreply@example.org"));
    ReflectionTestUtils.setField(
        magicLinkLoginService, "magicLinkFrontendBaseUrl", "https://app.oriso.org");
    ReflectionTestUtils.setField(
        magicLinkLoginService,
        "mailSender",
        (MagicLinkMailSender)
            (recipient, url, settings) ->
                sentMails.add(new SentMail(recipient, url, settings.host(), settings.from())));
    when(oneTimeTokenStore.claim(anyString(), anyString())).thenReturn(Optional.empty());
  }

  // --- requestMagicLink ---

  @Test
  void requestMagicLink_Should_ReturnAccepted_When_UsernameIsBlank() {
    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("  ");

    assertThat(result).isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  @Test
  void requestMagicLink_Should_ReturnAccepted_When_UsernameIsNull() {
    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink(null);

    assertThat(result).isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  @Test
  void requestMagicLink_Should_ReturnAccepted_When_AccountNotFound() {
    when(userService.findUserByUsername(anyString())).thenReturn(Optional.empty());
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());

    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("unknown-user");

    assertThat(result).isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  @Test
  void requestMagicLink_Should_ReturnNotEnabled_When_MagicLinkDisabledForUser() {
    User user = new User();
    user.setUserId("user-1");
    user.setUsername("testuser");
    user.setMagicLinkLoginEnabled(Boolean.FALSE);
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("testuser");

    assertThat(result).isEqualTo(MagicLinkRequestResult.NOT_ENABLED);
  }

  @Test
  void requestMagicLink_Should_ReturnAccepted_When_MagicLinkEnabledIsNull() {
    // When magicLinkLoginEnabled is null (not explicitly set) the service treats it as not
    // enabled — falls through to ACCEPTED since Boolean.FALSE.equals(null) is false
    // and isMagicLinkAllowedForAccount requires Boolean.TRUE
    User user = new User();
    user.setUserId("user-1");
    user.setUsername("testuser");
    user.setMagicLinkLoginEnabled(null);
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("testuser");

    assertThat(result).isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  @Test
  void requestMagicLink_Should_ReturnNotEnabled_When_MagicLinkDisabledForConsultant() {
    when(userService.findUserByUsername(anyString())).thenReturn(Optional.empty());
    Consultant consultant = new Consultant();
    consultant.setId("consultant-1");
    consultant.setUsername("consultant");
    consultant.setMagicLinkLoginEnabled(Boolean.FALSE);
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.of(consultant));

    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("consultant");

    assertThat(result).isEqualTo(MagicLinkRequestResult.NOT_ENABLED);
  }

  @Test
  void requestMagicLink_Should_SendThroughDeploymentSmtp() {
    when(userService.findUserByUsername("testuser"))
        .thenReturn(Optional.of(validUserWithMagicLinkEnabled()));

    assertThat(magicLinkLoginService.requestMagicLink("testuser"))
        .isEqualTo(MagicLinkRequestResult.ACCEPTED);

    assertThat(sentMails).hasSize(1);
    SentMail mail = sentMails.get(0);
    assertThat(mail.recipient()).isEqualTo("real@example.com");
    assertThat(mail.host()).isEqualTo("deployment-smtp.example.org");
    assertThat(mail.from()).isEqualTo("noreply@example.org");
    assertThat(mail.url()).matches("https://app\\.oriso\\.org/login\\?magicToken=[0-9a-f]{64}");
  }

  @Test
  void requestMagicLink_Should_NotIssueToken_When_DeploymentSmtpIsIncomplete() {
    when(userService.findUserByUsername("testuser"))
        .thenReturn(Optional.of(validUserWithMagicLinkEnabled()));
    when(platformSmtpSettings.requireConfigured())
        .thenThrow(
            new IllegalStateException("Platform SMTP is not configured: smtp.host (SMTP_HOST)"));

    assertThat(magicLinkLoginService.requestMagicLink("testuser"))
        .isEqualTo(MagicLinkRequestResult.ACCEPTED);

    assertThat(sentMails).isEmpty();
    verify(oneTimeTokenStore, never())
        .store(anyString(), anyString(), anyString(), any(Instant.class), anyBoolean());
  }

  // --- consumeMagicLink ---

  @Test
  void consumeMagicLink_Should_ReturnEmpty_When_TokenIsBlank() {
    Optional<IdentitySession> result = magicLinkLoginService.consumeMagicLink("  ");

    assertThat(result).isEmpty();
  }

  @Test
  void consumeMagicLink_Should_ReturnEmpty_When_TokenIsNull() {
    Optional<IdentitySession> result = magicLinkLoginService.consumeMagicLink(null);

    assertThat(result).isEmpty();
  }

  @Test
  void consumeMagicLink_Should_ReturnEmpty_When_TokenNotFound() {
    Optional<IdentitySession> result = magicLinkLoginService.consumeMagicLink("non-existent-token");

    assertThat(result).isEmpty();
  }

  @Test
  void consumeMagicLink_Should_ReturnEmpty_When_TokenExchangeFails() {
    Optional<IdentitySession> result = magicLinkLoginService.consumeMagicLink("some-random-token");

    assertThat(result).isEmpty();
  }

  // ── isMagicLinkAllowedForAccount paths ────────────────────────────────────

  @Test
  void requestMagicLink_Should_ReturnAccepted_When_EmailIsBlankOnUser() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("  ");
    user.setMagicLinkLoginEnabled(Boolean.TRUE);
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    // email is blank → isMagicLinkAllowedForAccount returns false → ACCEPTED
    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("testuser");

    assertThat(result).isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  @Test
  void requestMagicLink_Should_ReturnAccepted_When_EmailEndsWithDummySuffix() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("testuser@beratungcaritas.de");
    user.setMagicLinkLoginEnabled(Boolean.TRUE);
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    MagicLinkRequestResult result = magicLinkLoginService.requestMagicLink("testuser");

    assertThat(result).isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  // ── consumeMagicLink — shared token store ─────────────────────────────────

  @Test
  void consumeMagicLink_Should_ReturnEmpty_When_TokenIsExpired() {
    when(oneTimeTokenStore.claim("magic-login", "not-stored-token")).thenReturn(Optional.empty());
    assertThat(magicLinkLoginService.consumeMagicLink("not-stored-token")).isEmpty();
  }

  @Test
  void consumeMagicLink_Should_ReturnEmpty_When_AdminLoginFails() {
    OneTimeTokenStore.TokenClaim claim = validClaim("user-keycloak-id");
    when(oneTimeTokenStore.claim("magic-login", "valid-token")).thenReturn(Optional.of(claim));
    when(identitySessionExchange.exchangeForUser("user-keycloak-id")).thenReturn(Optional.empty());

    assertThat(magicLinkLoginService.consumeMagicLink("valid-token")).isEmpty();
    verify(oneTimeTokenStore).restore("magic-login", "valid-token", claim, false);
  }

  // ── NPE guard: emailDummySuffix = null ───────────────────────────────────

  @Test
  void requestMagicLink_Should_NotThrowNPE_When_EmailDummySuffixIsNull() {
    // Bug: line 162 calls target.getEmail().endsWith(emailDummySuffix) without null guard.
    // If emailDummySuffix is null, endsWith(null) throws NPE.
    // Expected: graceful ACCEPTED, not NPE.
    ReflectionTestUtils.setField(magicLinkLoginService, "emailDummySuffix", null);
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("real@example.com");
    user.setMagicLinkLoginEnabled(Boolean.TRUE);
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.of(user));

    assertThatCode(() -> magicLinkLoginService.requestMagicLink("testuser"))
        .doesNotThrowAnyException();
    assertThat(magicLinkLoginService.requestMagicLink("testuser"))
        .isEqualTo(MagicLinkRequestResult.ACCEPTED);
  }

  // ── Token single-use ─────────────────────────────────────────────────────

  @Test
  void consumeMagicLink_Should_RestoreToken_When_ExchangeThrowsException() {
    OneTimeTokenStore.TokenClaim claim = validClaim("user-keycloak-id");
    when(oneTimeTokenStore.claim("magic-login", "valid-token")).thenReturn(Optional.of(claim));

    when(identitySessionExchange.exchangeForUser("user-keycloak-id"))
        .thenThrow(new RuntimeException("connection refused"));

    assertThat(magicLinkLoginService.consumeMagicLink("valid-token")).isEmpty();

    verify(oneTimeTokenStore).restore("magic-login", "valid-token", claim, false);
  }

  // ── Token restore on transient exchange failure ───────────────────────────

  @Test
  void consumeMagicLink_Should_RestoreToken_When_ExchangeReturnsNull() {
    OneTimeTokenStore.TokenClaim claim = validClaim("user-keycloak-id");
    when(oneTimeTokenStore.claim("magic-login", "retry-token")).thenReturn(Optional.of(claim));

    when(identitySessionExchange.exchangeForUser("user-keycloak-id")).thenReturn(Optional.empty());

    Optional<IdentitySession> result = magicLinkLoginService.consumeMagicLink("retry-token");

    assertThat(result).isEmpty();
    verify(oneTimeTokenStore).restore("magic-login", "retry-token", claim, false);
  }

  // ── Redis failure boundary ────────────────────────────────────────────────

  @Test
  void consumeMagicLink_Should_FailClosed_When_TokenStoreIsUnavailable() {
    when(oneTimeTokenStore.claim("magic-login", "token"))
        .thenThrow(new IllegalStateException("redis unavailable"));

    assertThat(magicLinkLoginService.consumeMagicLink("token")).isEmpty();
  }

  // ── resolveAccount — username encoding fallback ───────────────────────────

  @Test
  void requestMagicLink_Should_ReturnNotEnabled_When_UserFoundByEncodedUsername() {
    // resolveAccount tries the Base32-encoded variant if original lookup returns empty.
    // UsernameTranscoder.encodeUsername("testuser") → "enc.<base32>".
    // Stub the encoded form to return our user.
    de.caritas.cob.userservice.api.helper.UsernameTranscoder transcoder =
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder();
    String encoded = transcoder.encodeUsername("testuser");

    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("testuser@example.com");
    user.setMagicLinkLoginEnabled(Boolean.FALSE);
    when(userService.findUserByUsername("testuser")).thenReturn(Optional.empty());
    when(userService.findUserByUsername(encoded)).thenReturn(Optional.of(user));

    // NOT_ENABLED proves account was resolved via encoded fallback
    assertThat(magicLinkLoginService.requestMagicLink("testuser"))
        .isEqualTo(MagicLinkRequestResult.NOT_ENABLED);
  }

  @Test
  void requestMagicLink_Should_FallbackToConsultant_When_UserNotFoundByAnyVariant() {
    // resolveAccount falls through user lookups and tries consultant.
    when(userService.findUserByUsername(anyString())).thenReturn(Optional.empty());
    Consultant consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setUsername("consultant1");
    consultant.setEmail("consultant@example.com");
    consultant.setMagicLinkLoginEnabled(Boolean.FALSE);
    when(consultantService.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.of(consultant));

    assertThat(magicLinkLoginService.requestMagicLink("consultant1"))
        .isEqualTo(MagicLinkRequestResult.NOT_ENABLED);
  }

  // ── consumeMagicLink — happy path returns provider-neutral session ────────

  @Test
  void consumeMagicLink_Should_ReturnDto_When_TokenValidAndExchangeSucceeds() {
    when(oneTimeTokenStore.claim("magic-login", "happy-token"))
        .thenReturn(Optional.of(validClaim("user-kc-id")));

    IdentitySession session = identitySession();
    when(identitySessionExchange.exchangeForUser("user-kc-id")).thenReturn(Optional.of(session));

    Optional<IdentitySession> result = magicLinkLoginService.consumeMagicLink("happy-token");

    assertThat(result).contains(session);
  }

  // ── exchangeTokenForUser catch block — admin succeeds, exchange throws ────

  @Test
  void consumeMagicLink_Should_RestoreToken_When_AdminSucceedsButExchangeThrows() {
    OneTimeTokenStore.TokenClaim claim = validClaim("user-id");
    when(oneTimeTokenStore.claim("magic-login", "exchange-fail-token"))
        .thenReturn(Optional.of(claim));

    when(identitySessionExchange.exchangeForUser("user-id"))
        .thenThrow(new RuntimeException("exchange failed"));

    Optional<IdentitySession> result =
        magicLinkLoginService.consumeMagicLink("exchange-fail-token");

    assertThat(result).isEmpty();
    verify(oneTimeTokenStore).restore("magic-login", "exchange-fail-token", claim, false);
  }

  // ── resolveAccount — decoded username fallback (lines 128-130) ───────────

  @Test
  void requestMagicLink_Should_ReturnNotEnabled_When_UserFoundByDecodedUsername() {
    // resolveAccount: decodeUsername(encoded) = original; encoded != original
    // → first lookup (encoded) fails → second lookup (decoded = original) succeeds
    de.caritas.cob.userservice.api.helper.UsernameTranscoder transcoder =
        new de.caritas.cob.userservice.api.helper.UsernameTranscoder();
    String original = "testuser";
    String encoded = transcoder.encodeUsername(original);

    User user = new User();
    user.setUserId("u-1");
    user.setUsername(original);
    user.setEmail("testuser@example.com");
    user.setMagicLinkLoginEnabled(Boolean.FALSE);

    when(userService.findUserByUsername(encoded)).thenReturn(Optional.empty());
    when(userService.findUserByUsername(original)).thenReturn(Optional.of(user));

    assertThat(magicLinkLoginService.requestMagicLink(encoded))
        .isEqualTo(MagicLinkRequestResult.NOT_ENABLED);
  }

  private OneTimeTokenStore.TokenClaim validClaim(String subjectId) {
    return new OneTimeTokenStore.TokenClaim(subjectId, Instant.now().plusSeconds(900));
  }

  private IdentitySession identitySession() {
    return new IdentitySession(
        "access-token", 300, 600, "refresh-token", "Bearer", "session-state", "openid profile");
  }

  private User validUserWithMagicLinkEnabled() {
    User user = new User();
    user.setUserId("u-1");
    user.setUsername("testuser");
    user.setEmail("real@example.com");
    user.setMagicLinkLoginEnabled(Boolean.TRUE);
    return user;
  }
}
