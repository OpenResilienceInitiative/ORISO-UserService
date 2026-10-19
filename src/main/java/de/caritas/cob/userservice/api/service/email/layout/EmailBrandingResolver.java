package de.caritas.cob.userservice.api.service.email.layout;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Resolves the branding of one outgoing mail under ADR-026 (ORISO-UserService#1252).
 *
 * <p>Resolution order, each step degrading independently:
 *
 * <ul>
 *   <li><b>Name</b> — tenant name → configured platform name.
 *   <li><b>Logo</b> — tenant {@code theming.logo} → tenant {@code theming.associationLogo} →
 *       configured platform logo → no image at all, in which case the layout renders the text
 *       wordmark. Stored inline images are exposed through TenantService's public HTTP asset
 *       endpoint because mail clients block {@code data:} URIs.
 *   <li><b>Brand colour</b> — tenant {@code theming.primaryColor} → platform theming {@code
 *       primaryColor} → neutral installation default {@value #DEFAULT_PRIMARY_COLOR}. The colour is
 *       used as configured; the button label and link colour are derived from it in {@link
 *       EmailBranding} (see {@link #resolveAccentColor(RestrictedTenantDTO)}).
 *   <li><b>Footer</b> — the imprint/privacy URLs built from the same tenant resolved above (via
 *       {@link TenantTemplateSupplier#getTenantBaseUrl(RestrictedTenantDTO)}, never from the
 *       ambient {@link TenantContext}). Platform mail uses the configured application URL. Missing
 *       tenant URLs fail instead of changing origin.
 * </ul>
 *
 * <p>A tenant-admin invite may be sent <em>before</em> the tenant exists, so a 404 uses platform
 * branding. Other tenant lookup failures stop the mail; they must not change its organisation.
 */
@Slf4j
@Component
public class EmailBrandingResolver {

  private final TenantService tenantService;
  private final TenantTemplateSupplier tenantTemplateSupplier;
  private final String platformName;
  private final String platformLogoUrl;
  private final String applicationBaseUrl;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  /**
   * Neutral installation default, used only when neither tenant nor platform theming has a usable
   * colour (ADR-026 amendment 2026-10-02). Not a brand colour; exempt from the too-pale rule.
   */
  static final String DEFAULT_PRIMARY_COLOR = "#000000";

  /** Bound on distinct keys held, so a pathological tenant id space cannot grow this unbounded. */
  private static final int MAX_CACHE_ENTRIES = 1000;

  /**
   * The supported maximum for {@code email.branding.cache-ttl-seconds}. This is the configuration
   * contract, not an arithmetic guard: the cache exists to collapse one batch, and source 2606d840
   * removed the previous 24-hour cache because a logo change stayed invisible until it expired.
   * Anything beyond ten seconds walks back that fix, so a larger configured value is clamped and
   * reported rather than honoured. It also keeps the nanosecond conversion far below overflow.
   */
  private static final long MAX_CACHE_TTL_SECONDS = 10L;

  private final long cacheTtlNanos;
  private final Map<CacheKey, CachedTenant> tenantCache = new ConcurrentHashMap<>();
  private final AtomicLong lookupSequence = new AtomicLong();

  private record CacheKey(Long tenantId, boolean pendingTenantAllowed) {}

  private record CachedTenant(
      RestrictedTenantDTO tenant, long storedAtNanos, long lookupSequence) {}

  /**
   * @param cacheTtlSeconds collapses the per-recipient lookups of one batch into a single remote
   *     call. Must stay short: source 2606d840 removed the 24-hour tenant cache precisely because a
   *     logo change stayed invisible in mail until it expired. A few seconds keeps a branding save
   *     effectively immediate while a digest run of N consultants costs one call instead of N. Set
   *     to 0 to disable caching entirely. Values above {@link #MAX_CACHE_TTL_SECONDS} are clamped
   *     to it, so a mis-typed TTL (milliseconds pasted into a seconds field, say) can neither
   *     overflow the nanosecond conversion into a negative value - which silently disabled the
   *     cache - nor pin stale branding for hours.
   */
  @Autowired
  public EmailBrandingResolver(
      @NonNull TenantService tenantService,
      @NonNull TenantTemplateSupplier tenantTemplateSupplier,
      @Value("${email.branding.name:}") String platformName,
      @Value("${email.branding.logo-url:}") String platformLogoUrl,
      @Value("${app.base.url:}") String applicationBaseUrl,
      @Value("${email.branding.cache-ttl-seconds:10}") long cacheTtlSeconds) {
    this.tenantService = tenantService;
    this.tenantTemplateSupplier = tenantTemplateSupplier;
    this.platformName = platformName;
    this.platformLogoUrl = platformLogoUrl;
    this.applicationBaseUrl = normalizeBaseUrl(applicationBaseUrl);
    URI configuredBase =
        firstAbsoluteUrl(this.applicationBaseUrl) == null
            ? null
            : URI.create(this.applicationBaseUrl);
    if (configuredBase == null
        || configuredBase.getUserInfo() != null
        || configuredBase.getRawQuery() != null
        || configuredBase.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "app.base.url must be an absolute HTTP(S) URL for email branding");
    }
    this.cacheTtlNanos = boundedTtlSeconds(cacheTtlSeconds) * 1_000_000_000L;
  }

  /** Clamp before scaling, so the conversion below can never overflow into a negative TTL. */
  private static long boundedTtlSeconds(long configuredSeconds) {
    if (configuredSeconds > MAX_CACHE_TTL_SECONDS) {
      log.warn(
          "email.branding.cache-ttl-seconds={} exceeds the supported maximum of {}s and was clamped."
              + " A longer branding cache delays tenant logo and colour changes in outgoing mail.",
          configuredSeconds,
          MAX_CACHE_TTL_SECONDS);
      return MAX_CACHE_TTL_SECONDS;
    }
    return Math.max(0L, configuredSeconds);
  }

  /** Caching disabled: every resolve performs its own lookup. */
  public EmailBrandingResolver(
      @NonNull TenantService tenantService,
      @NonNull TenantTemplateSupplier tenantTemplateSupplier,
      String platformName,
      String platformLogoUrl,
      String applicationBaseUrl) {
    this(
        tenantService,
        tenantTemplateSupplier,
        platformName,
        platformLogoUrl,
        applicationBaseUrl,
        0L);
  }

  /**
   * @param tenantId tenant the mail belongs to, or {@code null} when it is not (yet) known
   */
  public EmailBranding resolve(Long tenantId) {
    return resolveBranding(tenantId, false);
  }

  /** Only for invitations and DPA mail whose tenant may have a reserved id before creation. */
  public EmailBranding resolvePendingTenant(Long tenantId) {
    return resolveBranding(tenantId, true);
  }

  /** The configured product name is shared by platform subjects and the offered-by line. */
  public String platformName() {
    if (isBlank(platformName)) {
      throw new IllegalStateException(
          "EMAIL_BRANDING_NAME is missing; configure the platform name before sending email");
    }
    return platformName.trim();
  }

  private EmailBranding resolveBranding(Long tenantId, boolean pendingTenantAllowed) {
    String configuredPlatformName = platformName();
    RestrictedTenantDTO tenant = loadTenantQuietly(tenantId, pendingTenantAllowed);
    Theming theming = tenant == null ? null : tenant.getTheming();

    String brandName =
        tenant != null && !isBlank(tenant.getName()) ? tenant.getName() : configuredPlatformName;

    String logoUrl = resolveLogoUrl(tenant, theming);
    EmailLogoDimensions dimensions = resolveLogoDimensions(tenant, theming, logoUrl);
    return new EmailBranding(
        brandName,
        logoUrl,
        resolveAccentColor(tenant),
        resolveFooterUrl(tenant, "/impressum"),
        resolveFooterUrl(tenant, "/datenschutz"),
        dimensions == null ? null : dimensions.width(),
        dimensions == null ? null : dimensions.height());
  }

  /** Notification links must belong to the exact existing recipient tenant. */
  public EmailBranding resolveNotification(long tenantId, String mailBaseUrl) {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("Notification tenant id is missing");
    }
    String configuredPlatformName = platformName();
    RestrictedTenantDTO tenant = tenantService.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || !Objects.equals(tenant.getId(), tenantId)) {
      throw new IllegalArgumentException("Notification tenant is unavailable");
    }
    String configuredBase =
        multitenancyEnabled ? tenantTemplateSupplier.getTenantBaseUrl(tenant) : applicationBaseUrl;
    String expected = requireBaseUrl(configuredBase);
    if (!expected.equals(requireBaseUrl(mailBaseUrl))) {
      throw new IllegalArgumentException("Notification URL does not match recipient tenant");
    }
    Theming theming = tenant.getTheming();
    String brandName = !isBlank(tenant.getName()) ? tenant.getName() : configuredPlatformName;
    String logoUrl = resolveLogoUrl(tenant, theming);
    EmailLogoDimensions dimensions = resolveLogoDimensions(tenant, theming, logoUrl);
    return new EmailBranding(
        brandName,
        logoUrl,
        resolveAccentColor(tenant),
        expected + "/impressum",
        expected + "/datenschutz",
        dimensions == null ? null : dimensions.width(),
        dimensions == null ? null : dimensions.height());
  }

  private static String requireBaseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalArgumentException("Notification URL is missing");
    }
    String url = normalizeBaseUrl(value);
    try {
      URI uri = URI.create(url);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("Notification URL is invalid");
      }
      return url;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Notification URL is invalid");
    }
  }

  private String resolveLogoUrl(RestrictedTenantDTO tenant, Theming theming) {
    if (theming != null) {
      String tenantLogo = firstPartyLogo(theming.getLogo(), theming.getAssociationLogo());
      if (tenantLogo != null) {
        return tenantLogo;
      }
      // The public asset endpoint selects logo whenever it is non-null, even if it cannot decode
      // that value. Only fall through to associationLogo when that endpoint does the same.
      String servedLogo =
          theming.getLogo() != null ? theming.getLogo() : theming.getAssociationLogo();
      if (isStoredImage(servedLogo)) {
        String baseUrl = firstAbsoluteUrl(applicationBaseUrl);
        if (!isBlank(baseUrl) && tenant != null && tenant.getId() != null) {
          Long assetTenantId = tenant.getId();
          return baseUrl + "/service/tenant/public/branding/" + assetTenantId + "/logo";
        }
      }
    }
    return firstPartyLogo(platformLogoUrl);
  }

  private EmailLogoDimensions resolveLogoDimensions(
      RestrictedTenantDTO tenant, Theming theming, String logoUrl) {
    if (tenant == null
        || theming == null
        || logoUrl == null
        || firstPartyLogo(theming.getLogo(), theming.getAssociationLogo()) != null) return null;
    String baseUrl = firstAbsoluteUrl(applicationBaseUrl);
    if (baseUrl == null
        || tenant.getId() == null
        || !logoUrl.equals(baseUrl + "/service/tenant/public/branding/" + tenant.getId() + "/logo"))
      return null;
    // Match the endpoint exactly: non-null logo takes precedence, even when invalid.
    return EmailLogoDimensions.read(
        theming.getLogo() != null ? theming.getLogo() : theming.getAssociationLogo());
  }

  private boolean isStoredImage(String value) {
    return !isBlank(value) && firstAbsoluteUrl(value) == null;
  }

  /**
   * Mail images stay on the configured application origin, without third-party tracking fetches.
   */
  private String firstPartyLogo(String... candidates) {
    String configuredBase = firstAbsoluteUrl(applicationBaseUrl);
    if (configuredBase == null) {
      return null;
    }
    URI origin = URI.create(configuredBase);
    for (String candidate : candidates) {
      String absolute = firstAbsoluteUrl(candidate);
      if (absolute == null) {
        continue;
      }
      try {
        URI image = URI.create(absolute);
        if (image.getUserInfo() == null
            && origin.getHost() != null
            && origin.getHost().equalsIgnoreCase(image.getHost())
            && origin.getScheme().equalsIgnoreCase(image.getScheme())
            && effectivePort(origin) == effectivePort(image)) {
          return absolute;
        }
      } catch (IllegalArgumentException ignored) {
        // Invalid stored URL cannot become an outgoing image reference.
      }
    }
    return null;
  }

  /**
   * The brand colour of the mail: the stripe and button fill, used as configured (ADR-026 amendment
   * 2026-10-02, the same token logic as the web frontend).
   *
   * <p>Chain: the tenant's {@code theming.primaryColor} → the platform tenant's {@code
   * theming.primaryColor} → {@link #DEFAULT_PRIMARY_COLOR}. A colour counts as usable under the web
   * app's own rule, {@link EmailColors#usablePrimary(String)}: a hex colour that is not too pale. A
   * light chromatic colour such as yellow is usable; the button label is derived from it later (see
   * {@link EmailBranding#buttonLabelColor()}), not by rejecting the colour. TenantService already
   * inherits missing theming values from the platform tenant, so the second step only matters for a
   * tenant colour that is present but unusable (near-grey) or a tenant that does not exist yet.
   *
   * <p>The default is pure black, a neutral installation value and not a brand colour, so an
   * installation without any usable colour sends black mail instead of failing or borrowing another
   * installation's red. It is chroma 0, so it deliberately bypasses the "too pale" filter; its
   * button label is white (21:1).
   *
   * <p>{@code theming.accent} and {@code theming.signal} are read from TenantService but not used:
   * mail has no dark rendering yet (the layout opts out with {@code color-scheme: light only}), and
   * {@code secondaryColor} is not a candidate because ORISO-Admin writes it as {@code null}. The
   * SMTP setting {@code globalSmtpEmailThemeColor} is not a candidate either: a transport setting
   * is not a design token.
   */
  private String resolveAccentColor(RestrictedTenantDTO tenant) {
    Theming theming = tenant == null ? null : tenant.getTheming();
    String configured = theming == null ? null : theming.getPrimaryColor();
    String own = EmailColors.usablePrimary(configured);
    if (own != null) {
      return own;
    }
    if (!isBlank(configured)) {
      log.warn(
          "Tenant email primary color {} is not usable (invalid or too pale); using the platform"
              + " theming color",
          configured);
    }
    boolean isPlatformTenant =
        tenant != null && TenantContext.TECHNICAL_TENANT_ID.equals(tenant.getId());
    if (!isPlatformTenant) {
      RestrictedTenantDTO platform = loadPlatformTenantQuietly();
      Theming platformTheming = platform == null ? null : platform.getTheming();
      String inherited =
          EmailColors.usablePrimary(
              platformTheming == null ? null : platformTheming.getPrimaryColor());
      if (inherited != null) {
        return inherited;
      }
    }
    log.warn(
        "Neither the tenant nor the platform theming has a usable primaryColor; sending mail with"
            + " the neutral default {}",
        DEFAULT_PRIMARY_COLOR);
    return DEFAULT_PRIMARY_COLOR;
  }

  private String resolveFooterUrl(RestrictedTenantDTO tenant, String fallbackPath) {
    // Platform mail uses the explicitly configured application origin. A missing tenant URL must
    // never silently switch to that origin, because it could point recipients at another tenant.
    if (tenant == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenant.getId())) {
      return applicationBaseUrl + fallbackPath;
    }
    String tenantBaseUrl = tenantTemplateSupplier.getTenantBaseUrl(tenant);
    String tenantUrl = isBlank(tenantBaseUrl) ? null : tenantBaseUrl + fallbackPath;
    String absolute = firstAbsoluteUrl(tenantUrl);
    if (absolute != null) {
      return absolute;
    }
    throw new IllegalStateException(
        "Tenant " + tenant.getId() + " has no valid base URL for email footer links");
  }

  private RestrictedTenantDTO loadTenantQuietly(Long tenantId, boolean pendingTenantAllowed) {
    if (cacheTtlNanos <= 0L) {
      return loadTenantUncached(tenantId, pendingTenantAllowed);
    }
    CacheKey key =
        new CacheKey(
            TenantContext.TECHNICAL_TENANT_ID.equals(tenantId) ? null : tenantId,
            pendingTenantAllowed);
    long now = System.nanoTime();
    CachedTenant cached = tenantCache.get(key);
    // Subtraction, not comparison of absolutes: nanoTime has no fixed epoch and may be negative.
    if (cached != null && now - cached.storedAtNanos() < cacheTtlNanos) {
      return cached.tenant();
    }
    long sequence = lookupSequence.incrementAndGet();
    RestrictedTenantDTO fresh = loadTenantUncached(tenantId, pendingTenantAllowed);
    // A null result is cached too: tenant-admin invites resolve to "no tenant yet", and that 404
    // is the normal case, not an error worth repeating once per recipient.
    synchronized (tenantCache) {
      CachedTenant newer = tenantCache.get(key);
      if (newer != null && newer.lookupSequence() > sequence) {
        // An earlier lookup cannot overwrite a retained result from a later lookup. Return its
        // own uncached result without extending the newer entry's retention or assuming a DB
        // version.
        return fresh;
      }
      if (!tenantCache.containsKey(key) && tenantCache.size() >= MAX_CACHE_ENTRIES) {
        tenantCache.clear();
      }
      // Capacity check and insertion must share the lock; concurrent misses can otherwise all
      // observe space and leave more than MAX_CACHE_ENTRIES distinct tenants in the cache.
      tenantCache.put(key, new CachedTenant(fresh, System.nanoTime(), sequence));
    }
    return fresh;
  }

  private RestrictedTenantDTO loadTenantUncached(Long tenantId, boolean pendingTenantAllowed) {
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return loadPlatformTenantQuietly();
    }
    try {
      // Mail must reflect saved branding changes, including logo removal, without cache expiry.
      return tenantService.getRestrictedTenantDataFresh(tenantId);
    } catch (HttpClientErrorException.NotFound exception) {
      if (!pendingTenantAllowed) {
        throw exception;
      }
      // A tenant-admin invite may reserve an id before the tenant exists. Other lookup failures
      // must stop the mail instead of branding and linking a known tenant as the platform.
      log.debug(
          "No tenant branding available for tenantId {} ({}) — using platform branding",
          tenantId,
          exception.getClass().getSimpleName());
      return loadPlatformTenantQuietly();
    }
  }

  private RestrictedTenantDTO loadPlatformTenantQuietly() {
    try {
      return tenantService.getPlatformTenantDataFresh();
    } catch (RuntimeException exception) {
      log.debug(
          "No platform branding available ({}) — using configured fallbacks",
          exception.getClass().getSimpleName());
      return null;
    }
  }

  /** Returns the first candidate that is an absolute http(s) URL, else {@code null}. */
  static String firstAbsoluteUrl(String... candidates) {
    if (candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      if (isBlank(candidate)) {
        continue;
      }
      String trimmed = candidate.trim();
      String lower = trimmed.toLowerCase(Locale.ROOT);
      if ((lower.startsWith("http://") || lower.startsWith("https://"))
          && trimmed.indexOf(' ') < 0
          && trimmed.indexOf('"') < 0) {
        try {
          // Validate before any caller uses URI.create or builds an asset/footer from this base.
          // A scheme prefix alone still accepts malformed escapes, brackets and missing hosts.
          URI uri = URI.create(trimmed);
          if (uri.getHost() != null) {
            return trimmed;
          }
        } catch (IllegalArgumentException ignored) {
          // Malformed configuration or stored links degrade to the text-only mail layout.
        }
      }
    }
    return null;
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String normalizeBaseUrl(String value) {
    if (isBlank(value)) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
