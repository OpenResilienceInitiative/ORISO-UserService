package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.service.helper.MailService.safeFailureReason;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.net.URI;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Sends one committed TAKEOVER notification through the tenant's explicit mail route. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverMailSender {
  private final @NonNull TenantSystemEmailRouteService routes;
  private final @NonNull TenantSystemEmailDelivery delivery;
  private final @NonNull TenantService tenants;
  private final @NonNull TenantTemplateSupplier tenantUrls;
  private final @NonNull CaseHandoverMailComposer composer;

  @Async
  public void send(CaseHandoverEmailNotification.Mail mail) {
    try {
      var route = routes.resolve(mail.tenantId());
      if (route.isEmpty()) return;
      RestrictedTenantDTO tenant = tenants.getRestrictedTenantData(mail.tenantId());
      if (tenant == null || !Objects.equals(tenant.getId(), mail.tenantId())) {
        throw new TenantSystemEmailRouteService.ConfigurationException(
            "Takeover email tenant is unavailable");
      }
      String baseUrl = tenantUrls.getTenantBaseUrl(tenant);
      if (!hasConfiguredTenantUrl(baseUrl)) {
        throw new TenantSystemEmailRouteService.ConfigurationException(
            "Takeover email requires a configured tenant URL");
      }
      var content = composer.compose(mail, baseUrl);
      var purpose =
          mail.outcome() == CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED
              ? TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED
              : TenantSystemEmailDelivery.Purpose.HANDOVER_CONFIRMED;
      if (!delivery.sendConfirmed(
          mail.tenantId(), route.get(), purpose, mail.recipient(), content)) {
        log.error("Takeover mail was rejected by platform SMTP for tenant {}", mail.tenantId());
      }
    } catch (TenantSystemEmailRouteService.ConfigurationException failure) {
      log.error(
          "Takeover mail configuration for tenant {}: {}", mail.tenantId(), failure.getMessage());
    } catch (RuntimeException failure) {
      // No recipient, case reference or untrusted provider message in logs.
      log.error(
          "Takeover mail failed for tenant {}: {}", mail.tenantId(), safeFailureReason(failure));
    }
  }

  private boolean hasConfiguredTenantUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) return false;
    try {
      URI url = URI.create(baseUrl);
      String host = url.getHost();
      return "https".equalsIgnoreCase(url.getScheme())
          && host != null
          && !host.startsWith("null.")
          && !host.startsWith(".");
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
