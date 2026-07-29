package de.caritas.cob.userservice.api.service.email.layout;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Theming;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
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
 *   <li><b>Accent colour</b> — tenant {@code primaryColor} → tenant {@code secondaryColor} → {@code
 *       globalSmtpEmailThemeColor} from the platform settings → {@link EmailColors#DEFAULT_ACCENT}.
 *       Contrast-safe foregrounds are derived from it in {@link EmailBranding}, so a light theme
 *       colour never yields light-on-light text.
 *   <li><b>Footer</b> — the imprint/privacy URLs already computed by {@link TenantTemplateSupplier}
 *       (reused, not re-derived) → the configured application base URL.
 * </ul>
 *
 * <p>Every remote lookup is best-effort. A tenant-admin invite is sent <em>before</em> the tenant
 * exists, so a 404 from TenantService is the normal case, not an error — the mail then simply uses
 * platform branding.
 *
 * <p>Note on {@code accent}/{@code signal}: the tenant theming contract consumed here ({@code
 * services/tenantservice.yaml → Theming}) exposes {@code logo}, {@code associationLogo}, {@code
 * favicon}, {@code primaryColor} and {@code secondaryColor}. Once TenantService publishes dedicated
 * accent/signal fields they slot into {@link #resolveAccentColor} without touching the layout.
 */
@Slf4j
@Component
public class EmailBrandingResolver {

  private static final String IMPRINT_KEY = "tenant_urlimpressum";
  private static final String PRIVACY_KEY = "tenant_urldatenschutz";

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
   * @param globalEmailThemeColor {@code globalSmtpEmailThemeColor} from the platform settings
   */
  public EmailBranding resolve(Long tenantId, String globalEmailThemeColor) {
    RestrictedTenantDTO tenant = loadTenantQuietly(tenantId);
    Theming theming = tenant == null ? null : tenant.getTheming();

    String brandName =
        tenant != null && !isBlank(tenant.getName())
            ? tenant.getName()
            : (isBlank(platformName) ? "ORISO" : platformName);

    return new EmailBranding(
        brandName,
        resolveLogoUrl(theming),
        resolveAccentColor(theming, globalEmailThemeColor),
        resolveFooterUrl(IMPRINT_KEY, "/impressum"),
        resolveFooterUrl(PRIVACY_KEY, "/datenschutz"));
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

  private String resolveAccentColor(Theming theming, String globalEmailThemeColor) {
    String color =
        theming == null
            ? EmailColors.normalize(globalEmailThemeColor)
            : EmailColors.firstValid(
                theming.getPrimaryColor(), theming.getSecondaryColor(), globalEmailThemeColor);
    return color == null ? EmailColors.DEFAULT_ACCENT : color;
  }

  private String resolveFooterUrl(String templateKey, String fallbackPath) {
    String fromTemplateSupplier = tenantTemplateAttributes().get(templateKey);
    String absolute = firstAbsoluteUrl(fromTemplateSupplier);
    if (absolute != null) {
      return absolute;
    }
    return isBlank(applicationBaseUrl) ? null : firstAbsoluteUrl(applicationBaseUrl + fallbackPath);
  }

  /**
   * Reuses the imprint/privacy URLs {@link TenantTemplateSupplier} already derives for the
   * MailService templates instead of deriving them a second time. It depends on {@link
   * TenantContext}, which is not populated on every code path, so failures degrade to an empty map.
   */
  private Map<String, String> tenantTemplateAttributes() {
    try {
      List<TemplateDataDTO> attributes = tenantTemplateSupplier.getTemplateAttributes();
      if (attributes == null) {
        return Map.of();
      }
      return attributes.stream()
          .filter(attribute -> attribute.getKey() != null && attribute.getValue() != null)
          .collect(
              Collectors.toMap(
                  TemplateDataDTO::getKey, TemplateDataDTO::getValue, (first, second) -> first));
    } catch (RuntimeException exception) {
      log.debug(
          "Could not resolve tenant template attributes for mail branding ({})",
          exception.getClass().getSimpleName());
      return Map.of();
    }
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
