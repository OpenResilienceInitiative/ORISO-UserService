package de.caritas.cob.userservice.api.service.email.layout;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.util.Locale;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the branding of one outgoing mail with sane fallbacks (ORISO-UserService#914).
 *
 * <p>Resolution order, each step degrading independently:
 *
 * <ul>
 *   <li><b>Name</b> — tenant name → configured platform name.
 *   <li><b>Logo</b> — tenant {@code theming.logo} → tenant {@code theming.associationLogo} →
 *       configured platform logo → no image at all, in which case the layout renders the text
 *       wordmark. Only absolute {@code http(s)} URLs are accepted: the tenant theming fields may
 *       hold inline base64 images, and {@code data:} URIs are blocked by Gmail and Outlook, so a
 *       base64 logo deliberately degrades to the wordmark instead of producing a broken image.
 *   <li><b>Accent colour</b> — tenant {@code theming.primaryColor} → {@link
 *       EmailColors#PLATFORM_ACCENT_DARK}. Contrast-safe foregrounds are derived from it in {@link
 *       EmailBranding}, so a light theme colour never yields light-on-light text. See {@link
 *       #resolveAccentColor} for why the chain is exactly two steps long.
 *   <li><b>Footer</b> — the imprint/privacy URLs built from the same tenant resolved above (via
 *       {@link TenantTemplateSupplier#getTenantBaseUrl(RestrictedTenantDTO)}, never from the
 *       ambient {@link TenantContext}) → the configured application base URL.
 * </ul>
 *
 * <p>Every remote lookup is best-effort. A tenant-admin invite is sent <em>before</em> the tenant
 * exists, so a 404 from TenantService is the normal case, not an error — the mail then simply uses
 * platform branding.
 */
@Slf4j
@Component
public class EmailBrandingResolver {

  private final TenantService tenantService;
  private final TenantTemplateSupplier tenantTemplateSupplier;
  private final String platformName;
  private final String platformLogoUrl;
  private final String applicationBaseUrl;

  public EmailBrandingResolver(
      @NonNull TenantService tenantService,
      @NonNull TenantTemplateSupplier tenantTemplateSupplier,
      @Value("${email.branding.name:ORISO}") String platformName,
      @Value("${email.branding.logo-url:}") String platformLogoUrl,
      @Value("${app.base.url:}") String applicationBaseUrl) {
    this.tenantService = tenantService;
    this.tenantTemplateSupplier = tenantTemplateSupplier;
    this.platformName = platformName;
    this.platformLogoUrl = platformLogoUrl;
    this.applicationBaseUrl = normalizeBaseUrl(applicationBaseUrl);
  }

  /**
   * @param tenantId tenant the mail belongs to, or {@code null} when it is not (yet) known
   */
  public EmailBranding resolve(Long tenantId) {
    RestrictedTenantDTO tenant = loadTenantQuietly(tenantId);
    Theming theming = tenant == null ? null : tenant.getTheming();

    String brandName =
        tenant != null && !isBlank(tenant.getName())
            ? tenant.getName()
            : (isBlank(platformName) ? "ORISO" : platformName);

    return new EmailBranding(
        brandName,
        resolveLogoUrl(theming),
        resolveAccentColor(theming),
        resolveFooterUrl(tenant, "/impressum"),
        resolveFooterUrl(tenant, "/datenschutz"));
  }

  private String resolveLogoUrl(Theming theming) {
    if (theming != null) {
      String tenantLogo = firstAbsoluteUrl(theming.getLogo(), theming.getAssociationLogo());
      if (tenantLogo != null) {
        return tenantLogo;
      }
    }
    return firstAbsoluteUrl(platformLogoUrl);
  }

  /**
   * The accent of the <b>light</b> rendering — the only rendering the platform ships today.
   *
   * <p>Chain: {@code theming.primaryColor} → {@link EmailColors#PLATFORM_ACCENT_DARK}. Two steps,
   * deliberately, per the binding decision on ORISO-UserService#914:
   *
   * <ul>
   *   <li>Light rendering uses the <em>dark</em> accent. {@code primaryColor} is exactly that — a
   *       light-mode token — so it is the tenant-level input and needs no further candidates.
   *   <li>{@code secondaryColor} is <b>not</b> a candidate. ORISO-Admin's {@code buildSeedUpdate}
   *       writes it as {@code null} on every theming save, so a step reading it could never resolve
   *       and would only obscure which value actually reaches the mail.
   *   <li>The SMTP setting {@code globalSmtpEmailThemeColor} ("E-Mail Designfarbe") is <b>not</b> a
   *       candidate either. The mail follows the product colour rule and nothing else; an SMTP
   *       transport setting is not a design token.
   * </ul>
   *
   * <p><b>Seam for the dark rendering — the single place it plugs in.</b> The colour rule says a
   * dark rendering must invert and use the <em>light</em> accent (the rose tone), never a darkened
   * or otherwise derived variant of the dark one. That value does not exist here: the tenant
   * contract this service consumes ({@code services/tenantservice.yaml → Theming}) exposes only
   * {@code logo}, {@code associationLogo}, {@code favicon}, {@code primaryColor} and {@code
   * secondaryColor}; {@code theming.accent} is dropped on save and is tracked as
   * OpenResilienceInitiative/ORISO-TenantService#154. Deriving a substitute rose here would hide
   * that gap, so nothing is derived and the mail renders light-only (see the {@code color-scheme:
   * light only} opt-out in {@code branded-email.html}).
   *
   * <p>Once #154 lands, the dark half is: add {@code resolveDarkRenderingAccent(theming)} next to
   * this method returning {@code firstValid(theming.getAccent())} with a light-accent platform
   * fallback, carry it as a second component on {@link EmailBranding}, and let {@link
   * BrandedEmailLayoutRenderer} emit it in a {@code prefers-color-scheme: dark} block alongside the
   * dark-surface neutrals. Nothing else in this resolver changes.
   */
  private String resolveAccentColor(Theming theming) {
    String color = theming == null ? null : EmailColors.firstValid(theming.getPrimaryColor());
    return color == null ? EmailColors.PLATFORM_ACCENT_DARK : color;
  }

  private String resolveFooterUrl(RestrictedTenantDTO tenant, String fallbackPath) {
    if (tenant != null) {
      String tenantBaseUrl = tenantTemplateSupplier.getTenantBaseUrl(tenant);
      String tenantUrl = isBlank(tenantBaseUrl) ? null : tenantBaseUrl + fallbackPath;
      String absolute = firstAbsoluteUrl(tenantUrl);
      if (absolute != null) {
        return absolute;
      }
    }
    return isBlank(applicationBaseUrl) ? null : firstAbsoluteUrl(applicationBaseUrl + fallbackPath);
  }

  private RestrictedTenantDTO loadTenantQuietly(Long tenantId) {
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return null;
    }
    try {
      return tenantService.getRestrictedTenantData(tenantId);
    } catch (RuntimeException exception) {
      // Expected for tenant-admin invites: the tenant is created only when the invite is accepted.
      log.debug(
          "No tenant branding available for tenantId {} ({}) — using platform branding",
          tenantId,
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
        return trimmed;
      }
    }
    return null;
  }

  private static String normalizeBaseUrl(String value) {
    if (isBlank(value)) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
