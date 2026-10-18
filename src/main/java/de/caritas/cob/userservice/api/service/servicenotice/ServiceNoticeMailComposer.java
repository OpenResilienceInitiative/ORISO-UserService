package de.caritas.cob.userservice.api.service.servicenotice;

import static de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeContent.UNRESOLVED;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.notification.TenantSystemEmailRouteService;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeAudience.MailTarget;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds one planned-notice mail; it never sends. */
@Service
@RequiredArgsConstructor
public class ServiceNoticeMailComposer {
  public record Composed(
      long tenantId,
      TenantSystemEmailRouteService.Route route,
      String recipient,
      OrisoEmailRenderer.RenderedEmail email) {}

  private final TenantService tenants;
  private final TenantTemplateSupplier tenantTemplates;
  private final EmailBrandingResolver branding;
  private final TenantEmailBrandValues brandValues;
  private final OrisoEmailRenderer renderer;
  private final TenantSystemEmailRouteService routes;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${feature.multitenancy.with.single.domain.enabled}")
  private boolean singleDomainMultitenancy;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  /**
   * Renders one recipient's mail from their own tenant's origin and branding. Empty when that
   * tenant switched system mail off; a broken tenant setup throws and is retried later.
   */
  public Optional<Composed> compose(ServiceNoticeCampaign campaign, MailTarget target) {
    long tenantId = target.tenantId();
    var route = routes.resolve(tenantId);
    if (route.isEmpty()) {
      return Optional.empty();
    }
    String baseUrl = baseUrl(tenantId);
    var values =
        new LinkedHashMap<>(
            brandValues.values(branding.resolveNotification(tenantId, baseUrl), tenantId));
    values.put("appUrl", baseUrl);
    values.put("settingsUrl", baseUrl + "/profile/einstellungen");
    values.put("unsubscribeUrl", baseUrl + "/profile/einstellungen/email");
    values.putAll(ServiceNoticeContent.maintenanceValues(campaign));
    var email = renderer.render(ServiceNoticeContent.TEMPLATE, target.tone(), values);
    if (UNRESOLVED.matcher(email.subject()).find()
        || UNRESOLVED.matcher(email.html()).find()
        || UNRESOLVED.matcher(email.text()).find()) {
      throw new IllegalStateException("Service notice template data is incomplete");
    }
    return Optional.of(new Composed(tenantId, route.get(), target.email(), email));
  }

  // Same origin rule as the self-help appointment mail: the recipient's own tenant, never a
  // platform fallback (no hard-coded URL defaults).
  private String baseUrl(long tenantId) {
    if (!multitenancyEnabled) {
      return requireBaseUrl(applicationBaseUrl);
    }
    var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || !Long.valueOf(tenantId).equals(tenant.getId())) {
      throw new IllegalStateException("Service notice sender tenant is unavailable");
    }
    if (!singleDomainMultitenancy && isBlank(tenant.getSubdomain())) {
      throw new IllegalStateException("Service notice sender tenant subdomain is missing");
    }
    return requireBaseUrl(tenantTemplates.getTenantBaseUrl(tenant));
  }

  private static String requireBaseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalStateException("Service notice app URL is missing");
    }
    URI url = URI.create(value);
    if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
        || url.getHost() == null
        || url.getUserInfo() != null
        || url.getQuery() != null
        || url.getFragment() != null) {
      throw new IllegalStateException("Service notice app URL is invalid");
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
