package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

/** Branding resolution and its fallbacks (ORISO-UserService#914). */
@ExtendWith(MockitoExtension.class)
class EmailBrandingResolverTest {

  @Mock private TenantService tenantService;
  @Mock private TenantTemplateSupplier tenantTemplateSupplier;

  /** Any dark colour: the platform theming colour most tests inherit when a tenant has none. */
  private static final String PLATFORM_COLOUR = "#1c4f8f";

  @BeforeEach
  void tenantUrls() {
    lenient().when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn("https://tenant.org");
    givenPlatformPrimaryColour(PLATFORM_COLOUR);
  }

  private void givenPlatformPrimaryColour(String primaryColor) {
    Theming theming = new Theming();
    theming.setPrimaryColor(primaryColor);
    RestrictedTenantDTO platform = new RestrictedTenantDTO();
    platform.setId(0L);
    platform.setTheming(primaryColor == null ? null : theming);
    lenient().when(tenantService.getPlatformTenantDataFresh()).thenReturn(platform);
  }

  private EmailBrandingResolver resolver(String platformLogoUrl) {
    return new EmailBrandingResolver(
        tenantService,
        tenantTemplateSupplier,
        "ORISO",
        platformLogoUrl,
        "https://app.example.org/");
  }

  private void givenNoTemplateAttributes() {
    lenient().when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn("https://tenant.org");
  }

  private static RestrictedTenantDTO tenant(String name, Theming theming) {
    RestrictedTenantDTO tenant = new RestrictedTenantDTO();
    tenant.setId(7L);
    tenant.setName(name);
    tenant.setTheming(theming);
    return tenant;
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void resolve_Should_RejectMissingConfiguredPlatformName(String configuredName) {
    EmailBrandingResolver subject =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, configuredName, "", "https://app.example.org");

    assertThatThrownBy(() -> subject.resolve(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMAIL_BRANDING_NAME");
  }

  @Test
  void resolve_Should_RejectMissingPlatformNameEvenForANamedTenant() {
    lenient().when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", null));
    EmailBrandingResolver subject =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "", "", "https://app.example.org");

    assertThatThrownBy(() -> subject.resolve(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMAIL_BRANDING_NAME");
  }

  @Test
  void resolve_Should_TrimTheConfiguredPlatformName() {
    EmailBrandingResolver subject =
        new EmailBrandingResolver(
            tenantService,
            tenantTemplateSupplier,
            "  Beratung Mitten  ",
            "",
            "https://app.example.org");

    assertThat(subject.resolve(null).brandName()).isEqualTo("Beratung Mitten");
  }

  @Test
  void resolve_Should_NotInventANameWhenCanonicalConfigurationIsAbsent() {
    new ApplicationContextRunner()
        .withBean(
            PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
        .withInitializer(
            context -> {
              context.getBeanFactory().registerSingleton("tenantService", tenantService);
              context
                  .getBeanFactory()
                  .registerSingleton("tenantTemplateSupplier", tenantTemplateSupplier);
            })
        .withUserConfiguration(EmailBrandingResolver.class)
        .withPropertyValues(
            "app.base.url=https://app.example.org",
            "multitenancy.enabled=false",
            "feature.multitenancy.with.single.domain.enabled=false")
        .run(
            context ->
                assertThatThrownBy(() -> context.getBean(EmailBrandingResolver.class).resolve(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EMAIL_BRANDING_NAME"));
  }

  @Test
  void resolve_Should_UseCanonicalConfiguredNameInSpringBinding() {
    new ApplicationContextRunner()
        .withBean(
            PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
        .withInitializer(
            context -> {
              context.getBeanFactory().registerSingleton("tenantService", tenantService);
              context
                  .getBeanFactory()
                  .registerSingleton("tenantTemplateSupplier", tenantTemplateSupplier);
            })
        .withUserConfiguration(EmailBrandingResolver.class)
        .withPropertyValues(
            "app.base.url=https://app.example.org",
            "email.branding.name=Beratung Mitten",
            "multitenancy.enabled=false",
            "feature.multitenancy.with.single.domain.enabled=false")
        .run(
            context ->
                assertThat(context.getBean(EmailBrandingResolver.class).resolve(null).brandName())
                    .isEqualTo("Beratung Mitten"));
  }

  @Test
  void notificationBrandingRequiresTheExactTenantUrl() {
    var resolved = tenant("Nord", null);
    resolved.setSubdomain("nord");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(resolved);
    when(tenantTemplateSupplier.getTenantBaseUrl(resolved))
        .thenReturn("https://nord.app.oriso.org");
    var subject = resolver("");
    ReflectionTestUtils.setField(subject, "multitenancyEnabled", true);

    var branding = subject.resolveNotification(7L, "https://nord.app.oriso.org");

    assertThat(branding.imprintUrl()).isEqualTo("https://nord.app.oriso.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://nord.app.oriso.org/datenschutz");
    assertThatThrownBy(() -> subject.resolveNotification(7L, "https://other.app.oriso.org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void notificationBrandingUsesCurrentSavedTenantWhenCachedBrandOrSubdomainIsStale(
      boolean subdomainChanged) {
    var oldTheme = new Theming();
    oldTheme.setLogo("https://app.example.org/old-logo.png");
    oldTheme.setPrimaryColor("#804030");
    var cachedTenant = tenant("Old tenant brand", oldTheme);
    cachedTenant.setSubdomain("old");
    var currentTheme = new Theming();
    currentTheme.setLogo("https://app.example.org/current-logo.png");
    currentTheme.setPrimaryColor("#1c4f8f");
    var currentTenant = tenant("Current tenant brand", currentTheme);
    currentTenant.setSubdomain(subdomainChanged ? "current" : "old");
    var currentUrl =
        subdomainChanged ? "https://current.app.example.org" : "https://old.app.example.org";
    lenient().when(tenantService.getRestrictedTenantData(7L)).thenReturn(cachedTenant);
    lenient().when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(currentTenant);
    lenient()
        .when(tenantTemplateSupplier.getTenantBaseUrl(cachedTenant))
        .thenReturn("https://old.app.example.org");
    lenient().when(tenantTemplateSupplier.getTenantBaseUrl(currentTenant)).thenReturn(currentUrl);
    var subject = resolver("");
    ReflectionTestUtils.setField(subject, "multitenancyEnabled", true);

    var branding = subject.resolveNotification(7L, currentUrl);

    assertThat(branding.brandName()).isEqualTo("Current tenant brand");
    assertThat(branding.logoUrl()).isEqualTo("https://app.example.org/current-logo.png");
    assertThat(branding.accentColor()).isEqualTo("#1c4f8f");
    assertThat(branding.imprintUrl()).isEqualTo(currentUrl + "/impressum");
    assertThat(branding.privacyUrl()).isEqualTo(currentUrl + "/datenschutz");
  }

  @Test
  void notificationBrandingDoesNotUsePlatformWhenTenantIsUnavailable() {
    assertThatThrownBy(() -> resolver("").resolveNotification(7L, "https://app.oriso.org"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant is unavailable");
  }

  @Test
  void notificationBrandingRequiresAConfiguredNameWhenTenantHasNone() {
    var subject =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "  ", "", "https://app.example.org/");

    assertThatThrownBy(() -> subject.resolveNotification(7L, "https://app.example.org"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EMAIL_BRANDING_NAME");
  }

  // --- logo -----------------------------------------------------------------------------

  @Test
  void resolve_Should_preferTheTenantLogo() {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setLogo("https://app.example.org/tenant.png");
    theming.setAssociationLogo("https://app.example.org/association.png");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    EmailBranding branding = resolver("https://app.example.org/platform.png").resolve(7L);

    assertThat(branding.logoUrl()).isEqualTo("https://app.example.org/tenant.png");
    assertThat(branding.brandName()).isEqualTo("Nord");
  }

  @Test
  void resolve_Should_fallBackToTheAssociationLogoThenThePlatformLogo() {
    givenNoTemplateAttributes();
    Theming associationOnly = new Theming();
    associationOnly.setAssociationLogo("https://app.example.org/association.png");
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(tenant("Nord", associationOnly));

    assertThat(resolver("https://app.example.org/platform.png").resolve(7L).logoUrl())
        .isEqualTo("https://app.example.org/association.png");

    assertThat(resolver("https://app.example.org/platform.png").resolve(null).logoUrl())
        .isEqualTo("https://app.example.org/platform.png");
  }

  /**
   * Even with a real subdomain (standard multitenancy), the id pins the tenant: the link neither
   * depends on the tenant's DNS name nor on host-based tenant resolution in TenantService.
   */
  @Test
  void resolve_Should_pinAStoredLogoToTheTenantId_Even_WhenTheTenantHasItsOwnSubdomain() {
    Theming base64Logo = new Theming();
    base64Logo.setLogo("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQ");
    base64Logo.setAssociationLogo("data:image/png;base64,iVBORw0KGgo=");
    RestrictedTenantDTO resolvedTenant = tenant("Nord", base64Logo);
    resolvedTenant.setId(12L);
    resolvedTenant.setSubdomain("nord");
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(resolvedTenant);
    lenient()
        .when(tenantTemplateSupplier.getTenantBaseUrl(resolvedTenant))
        .thenReturn("https://nord.app.example.org");

    EmailBranding branding = resolver("").resolve(12L);

    assertThat(branding.logoUrl())
        .isEqualTo("https://app.example.org/service/tenant/public/branding/12/logo");
    assertThat(branding.hasLogo()).isTrue();
  }

  @Test
  void resolve_Should_pinThePlatformTenantsStoredLogoToItsId_When_NoTenantIsKnown() {
    givenNoTemplateAttributes();
    Theming platformTheming = new Theming();
    platformTheming.setLogo("data:image/png;base64,iVBORw0KGgo=");
    platformTheming.setPrimaryColor(PLATFORM_COLOUR);
    RestrictedTenantDTO platform = tenant("ORISO", platformTheming);
    platform.setId(1L);
    when(tenantService.getPlatformTenantDataFresh()).thenReturn(platform);

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.logoUrl())
        .isEqualTo("https://app.example.org/service/tenant/public/branding/1/logo");
  }

  /**
   * Measured on staging 2026-09-21/22: Träger there have an empty subdomain, so the tenant base URL
   * degenerates to {@code https://.<host>}. The mail logo must still point at this tenant's own
   * image, pinned by id on the application origin once a valid tenant URL exists.
   */
  @Test
  void resolve_Should_rejectAnEmptySubdomainWithoutAValidTenantUrl() {
    Theming stored = new Theming();
    stored.setLogo("data:image/png;base64,iVBORw0KGgo=");
    RestrictedTenantDTO tenant12 = tenant("Traeger Zwoelf", stored);
    tenant12.setId(12L);
    tenant12.setSubdomain("");
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(tenant12);
    lenient()
        .when(tenantTemplateSupplier.getTenantBaseUrl(tenant12))
        .thenReturn("https://.online-beratung.neusta-integrate.de");

    assertThatThrownBy(() -> resolver("").resolve(12L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no valid base URL");
  }

  @Test
  void firstAbsoluteUrl_Should_rejectUrlsWithoutARealHost() {
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https://.example.org")).isNull();
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https://")).isNull();
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https:///x")).isNull();
    assertThat(
            EmailBrandingResolver.firstAbsoluteUrl(
                "https://.online-beratung.neusta-integrate.de/impressum"))
        .isNull();
    assertThat(EmailBrandingResolver.firstAbsoluteUrl("https://.example.org", "https://ok.org/a"))
        .isEqualTo("https://ok.org/a");
  }

  /** An invalid tenant URL must stop the footer from using a different origin. */
  @Test
  void resolve_Should_rejectAnInvalidTenantFooterOrigin() {
    RestrictedTenantDTO tenant12 = tenant("Traeger Zwoelf", null);
    tenant12.setId(12L);
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(tenant12);
    when(tenantTemplateSupplier.getTenantBaseUrl(tenant12))
        .thenReturn("https://.online-beratung.neusta-integrate.de");

    assertThatThrownBy(() -> resolver("").resolve(12L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no valid base URL");
  }

  @Test
  void resolve_Should_keepTheConfiguredPlatformLogo_When_TheTenantHasNoLogoOfItsOwn() {
    givenNoTemplateAttributes();
    RestrictedTenantDTO tenant12 = tenant("Traeger Zwoelf", new Theming());
    tenant12.setId(12L);
    when(tenantService.getRestrictedTenantDataFresh(12L)).thenReturn(tenant12);

    assertThat(resolver("https://app.example.org/platform.png").resolve(12L).logoUrl())
        .isEqualTo("https://app.example.org/platform.png");
  }

  @Test
  void resolve_Should_dropAnExternalLogoInsteadOfMakingTheRecipientFetchIt() {
    Theming theming = new Theming();
    theming.setLogo("https://tracker.example/logo.png");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    assertThat(resolver("https://tracker.example/platform.png").resolve(7L).logoUrl()).isNull();
  }

  @Test
  void resolve_Should_shareOneFreshLookupWithinTheShortCache() {
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", null));
    EmailBrandingResolver cached =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "ORISO", "", "https://app.example.org", 10L);

    cached.resolve(7L);
    cached.resolve(7L);

    verify(tenantService, times(1)).getRestrictedTenantDataFresh(7L);
  }

  @Test
  void slowEarlierLookupCannotReplaceTheNewerTenantInTheBatchCache() throws Exception {
    CountDownLatch firstLookupStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstLookup = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                firstLookupStarted.countDown();
                if (!releaseFirstLookup.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("First lookup was not released");
                }
                return tenant("Old saved name", null);
              }
              return tenant("New saved name", null);
            });
    EmailBrandingResolver cached =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "ORISO", "", "https://app.example.org", 10L);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> cached.resolve(7L));
      try {
        assertThat(firstLookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
        var second = executor.submit(() -> cached.resolve(7L));
        assertThat(second.get(5, TimeUnit.SECONDS).brandName()).isEqualTo("New saved name");
      } finally {
        releaseFirstLookup.countDown();
      }
      // The older caller receives its own completed lookup; it cannot reseed the shared cache.
      assertThat(first.get(5, TimeUnit.SECONDS).brandName()).isEqualTo("Old saved name");
      assertThat(cached.resolve(7L).brandName()).isEqualTo("New saved name");
      verify(tenantService, times(2)).getRestrictedTenantDataFresh(7L);
    }
  }

  @Test
  void batchCacheRetentionStartsAfterTheRemoteLookupCompletes() {
    AtomicLong remoteCompletion = new AtomicLong();
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenAnswer(
            invocation -> {
              remoteCompletion.set(System.nanoTime());
              return tenant("Nord", null);
            });
    EmailBrandingResolver cached =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "ORISO", "", "https://app.example.org", 10L);

    cached.resolve(7L);
    Map<?, ?> entries = (Map<?, ?>) ReflectionTestUtils.getField(cached, "tenantCache");
    assertThat(entries).hasSize(1);
    Object entry = entries.values().iterator().next();
    Long retainedSince = ReflectionTestUtils.invokeMethod(entry, "storedAtNanos");
    assertThat(retainedSince).isNotNull();
    // Monotonic differences also work when nanoTime's arbitrary origin is negative.
    assertThat(retainedSince - remoteCompletion.get()).isGreaterThanOrEqualTo(0L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://tracker.example/logo.png", ""})
  void resolve_Should_notLinkToAnAssetThePublicEndpointCannotServe(String primaryLogo) {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setLogo(primaryLogo);
    theming.setAssociationLogo("data:image/png;base64,iVBORw0KGgo=");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    assertThat(resolver("https://app.example.org/platform.png").resolve(7L).logoUrl())
        .isEqualTo("https://app.example.org/platform.png");
  }

  @Test
  void constructor_Should_limitBrandingCacheToTenSeconds() {
    EmailBrandingResolver cached =
        new EmailBrandingResolver(
            tenantService, tenantTemplateSupplier, "ORISO", "", "https://app.example.org", 300L);

    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(cached, "cacheTtlNanos"))
        .isEqualTo(10_000_000_000L);
  }

  @Test
  void constructor_Should_rejectAMissingApplicationUrl() {
    assertThatThrownBy(
            () -> new EmailBrandingResolver(tenantService, tenantTemplateSupplier, "ORISO", "", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.base.url");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://app.example.org",
        "https://user:secret@app.example.org",
        "https://app.example.org/?mail=1",
        "https://app.example.org/#mail"
      })
  void constructor_Should_rejectApplicationUrlsThatCannotBeSafeMailOrigins(String url) {
    assertThatThrownBy(
            () ->
                new EmailBrandingResolver(tenantService, tenantTemplateSupplier, "ORISO", "", url))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.base.url");
  }

  // --- colour ---------------------------------------------------------------------------

  /**
   * The mail follows the product colour rule and nothing else (#914, final decision): the light
   * rendering uses the dark accent, which on the tenant is {@code theming.primaryColor}.
   */
  @Test
  void resolve_Should_useTheTenantPrimaryColorAsTheLightRenderingAccent() {
    givenNoTemplateAttributes();
    Theming primary = new Theming();
    primary.setPrimaryColor("#123456");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", primary));

    assertThat(resolver("").resolve(7L).accentColor()).isEqualTo("#123456");
  }

  /**
   * {@code secondaryColor} is dead weight and must not silently become the mail colour: ORISO-Admin
   * writes it as {@code null} on every theming save, so a chain step reading it can never resolve
   * and would only hide the real fallback.
   */
  @Test
  void resolve_Should_ignoreTheTenantSecondaryColor() {
    givenNoTemplateAttributes();
    Theming secondaryOnly = new Theming();
    secondaryOnly.setSecondaryColor("#654321");
    when(tenantService.getRestrictedTenantDataFresh(8L)).thenReturn(tenant("Sued", secondaryOnly));

    assertThat(resolver("").resolve(8L).accentColor()).isEqualTo(PLATFORM_COLOUR);
  }

  /** The web app keeps a light brand colour and derives the label; mail does the same. */
  @ParameterizedTest
  @ValueSource(strings = {"#f8e71c", "#ffff00", "#ffd400", "#9ad6ff"})
  void resolve_Should_useALightButChromaticTenantColourAsConfigured(String light) {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setPrimaryColor(light);
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    EmailBranding branding = resolver("").resolve(7L);

    assertThat(branding.accentColor()).isEqualTo(light);
    assertThat(branding.buttonLabelColor()).as("a dark tone, not white").isNotEqualTo("#ffffff");
    assertThat(EmailColors.contrastRatio(branding.accentColor(), branding.buttonLabelColor()))
        .isGreaterThanOrEqualTo(4.5d);
    assertThat(EmailColors.contrastRatio(branding.linkColor(), "#ffffff"))
        .as("links on the white card are darkened, the button is not")
        .isGreaterThanOrEqualTo(4.5d);
  }

  /** The platform colour only steps in when the tenant colour is unusable under the web rule. */
  @ParameterizedTest
  @ValueSource(strings = {"not-a-color", "#808080", "#8a8a8a", "#000000", "#ffffff", "   "})
  void resolve_Should_usePlatformThemingColour_When_TenantColourIsUnusable(String unusable) {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setPrimaryColor(unusable);
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", theming));

    assertThat(resolver("").resolve(9L).accentColor()).isEqualTo(PLATFORM_COLOUR);
  }

  @Test
  void resolve_Should_usePlatformThemingColour_When_TenantHasNoThemingAtAll() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", null));

    assertThat(resolver("").resolve(9L).accentColor()).isEqualTo(PLATFORM_COLOUR);
  }

  @Test
  void resolve_Should_useThePlatformThemingColourForPlatformMail() {
    givenNoTemplateAttributes();

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.accentColor()).isEqualTo(PLATFORM_COLOUR);
    assertThat(branding.brandName()).isEqualTo("ORISO");
    assertThat(branding.logoUrl()).isNull();
  }

  /** A near-grey platform colour is unusable under the web rule too; the default takes over. */
  @Test
  void resolve_Should_applyTheWebRuleToThePlatformColourToo() {
    givenNoTemplateAttributes();
    givenPlatformPrimaryColour("#808080");

    assertThat(resolver("").resolve(null).accentColor()).isEqualTo("#000000");
  }

  /** No colour anywhere: the neutral installation default, black with a white label. */
  @Test
  void resolve_Should_useTheNeutralDefault_When_NoTenantNorPlatformColourIsUsable() {
    givenNoTemplateAttributes();
    givenPlatformPrimaryColour(null);
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", null));

    EmailBranding tenantMail = resolver("").resolve(9L);
    EmailBranding platformMail = resolver("").resolve(null);

    assertThat(tenantMail.accentColor()).isEqualTo("#000000");
    assertThat(tenantMail.buttonLabelColor()).isEqualTo("#ffffff");
    assertThat(platformMail.accentColor()).isEqualTo("#000000");
    assertThat(platformMail.buttonLabelColor()).isEqualTo("#ffffff");
  }

  @Test
  void resolve_Should_useTheNeutralDefault_When_ThePlatformTenantCannotBeLoaded() {
    givenNoTemplateAttributes();
    when(tenantService.getPlatformTenantDataFresh()).thenThrow(new IllegalStateException("down"));
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", null));

    EmailBranding branding = resolver("").resolve(9L);

    assertThat(branding.accentColor()).isEqualTo("#000000");
    assertThat(branding.buttonLabelColor()).isEqualTo("#ffffff");
  }

  /** Near-grey tenant colour: platform colour first, then the default, never the grey itself. */
  @Test
  void resolve_Should_fallBackToThePlatformThenToTheDefault_When_TenantColourIsNearGrey() {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setPrimaryColor("#8a8a8a");
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", theming));

    assertThat(resolver("").resolve(9L).accentColor()).isEqualTo(PLATFORM_COLOUR);

    givenPlatformPrimaryColour(null);
    EmailBranding branding = resolver("").resolve(9L);
    assertThat(branding.accentColor()).isEqualTo("#000000");
    assertThat(branding.buttonLabelColor()).isEqualTo("#ffffff");
  }

  @Test
  void resolve_Should_notUseTheTenantColourWhenItIsUsable_EvenIfPlatformHasOne() {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setPrimaryColor("#00897b");
    when(tenantService.getRestrictedTenantDataFresh(9L)).thenReturn(tenant("Ost", theming));

    assertThat(resolver("").resolve(9L).accentColor()).isEqualTo("#00897b");
  }

  /** accent and signal are read from the tenant (generated client) but not used for mail yet. */
  @Test
  void resolve_Should_readButNotUseAccentAndSignal() {
    givenNoTemplateAttributes();
    Theming theming = new Theming();
    theming.setPrimaryColor("#1c4f8f");
    theming.setAccent("#ffb3c7");
    theming.setSignal("#d93025");
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", theming));

    assertThat(theming.getAccent()).isEqualTo("#ffb3c7");
    assertThat(theming.getSignal()).isEqualTo("#d93025");
    assertThat(resolver("").resolve(7L).accentColor()).isEqualTo("#1c4f8f");
  }

  // --- degradation ----------------------------------------------------------------------

  /** Tenant-admin invites are sent before the tenant exists — a 404 is normal, not an error. */
  @Test
  void resolvePendingTenant_Should_usePlatformBranding_When_TenantDoesNotExistYet() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

    EmailBranding branding = resolver("").resolvePendingTenant(4711L);

    assertThat(branding.brandName()).isEqualTo("ORISO");
    assertThat(branding.accentColor()).isEqualTo(PLATFORM_COLOUR);
  }

  @Test
  void pendingTenantFallbackDoesNotMaskUnknownTenantInNormalResolution() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));
    EmailBrandingResolver cached = resolver("");

    assertThat(cached.resolvePendingTenant(7L).brandName()).isEqualTo("ORISO");
    assertThatThrownBy(() -> cached.resolve(7L))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    verify(tenantService, times(2)).getRestrictedTenantDataFresh(7L);
  }

  @Test
  void resolve_Should_rejectAnUnknownTenantForAnExistingAccount() {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenThrow(
            HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "nf", null, null, null));

    assertThatThrownBy(() -> resolver("").resolve(7L))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  @Test
  void resolve_Should_notReplaceATenantBrandAfterATenantServiceFailure() {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenThrow(new IllegalStateException("tenant service unavailable"));

    assertThatThrownBy(() -> resolver("").resolve(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant service unavailable");
  }

  @Test
  void resolve_ShouldLoadPlatformBranding_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    resolver("").resolve(null);

    org.mockito.Mockito.verify(tenantService).getPlatformTenantDataFresh();
  }

  // --- footer ---------------------------------------------------------------------------

  @Test
  void resolve_Should_buildTheImprintAndPrivacyUrlsFromTheResolvedTenantsOwnBaseUrl() {
    RestrictedTenantDTO resolvedTenant = tenant("Nord", null);
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(resolvedTenant);
    when(tenantTemplateSupplier.getTenantBaseUrl(resolvedTenant)).thenReturn("https://nord.org");

    EmailBranding branding = resolver("").resolve(7L);

    assertThat(branding.imprintUrl()).isEqualTo("https://nord.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://nord.org/datenschutz");
  }

  /**
   * Reproduces ORISO-UserService#915's follow-up bug: a super-admin invites a counsellor for tenant
   * 42. The ambient {@link de.caritas.cob.userservice.api.tenant.TenantContext} stays in the
   * super-admin's own (tenant-less) context throughout, but the footer must still carry tenant 42's
   * own imprint/privacy — not the technical context's, and not another tenant's — because it is
   * rendered together with tenant 42's name, logo and accent.
   */
  @Test
  void resolve_Should_useTheRequestedTenantsFooter_Even_WhenTheAmbientContextIsTechnical() {
    RestrictedTenantDTO tenant42 = tenant("Tenant42", null);
    when(tenantService.getRestrictedTenantDataFresh(42L)).thenReturn(tenant42);
    when(tenantTemplateSupplier.getTenantBaseUrl(tenant42)).thenReturn("https://tenant42.org");

    EmailBranding branding = resolver("").resolve(42L);

    assertThat(branding.brandName()).isEqualTo("Tenant42");
    assertThat(branding.imprintUrl()).isEqualTo("https://tenant42.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://tenant42.org/datenschutz");
  }

  @Test
  void resolve_Should_rejectMissingTenantBaseUrl() {
    givenNoTemplateAttributes();
    when(tenantService.getRestrictedTenantDataFresh(7L)).thenReturn(tenant("Nord", null));

    when(tenantTemplateSupplier.getTenantBaseUrl(any())).thenReturn(null);
    assertThatThrownBy(() -> resolver("").resolve(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no valid base URL");
  }

  @Test
  void resolve_Should_fallBackToTheApplicationBaseUrl_When_NoTenantIdIsKnown() {
    givenNoTemplateAttributes();

    EmailBranding branding = resolver("").resolve(null);

    assertThat(branding.imprintUrl()).isEqualTo("https://app.example.org/impressum");
    assertThat(branding.privacyUrl()).isEqualTo("https://app.example.org/datenschutz");
  }
}
