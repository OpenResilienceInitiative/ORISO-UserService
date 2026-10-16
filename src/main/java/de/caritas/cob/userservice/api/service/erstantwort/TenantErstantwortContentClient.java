package de.caritas.cob.userservice.api.service.erstantwort;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualContent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a Träger's authored Erstantwort texts from TenantService. The asker who triggers the send
 * has no tenant-admin rights, so the read runs as the technical service identity; the public
 * restricted-tenant DTO and {@code GET /tenant/{id}} do not carry these maps; only {@code GET
 * /tenantadmin/{id}} does. TenantService must let the technical identity read that endpoint,
 * otherwise this call is refused and the send falls back to platform defaults.
 */
@Component
@RequiredArgsConstructor
public class TenantErstantwortContentClient {

  private final @NonNull TenantAdminServiceApiControllerFactory tenantServiceFactory;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;

  /**
   * @return the tenant's multilingual content, or {@code null} when TenantService has none.
   */
  public MultilingualContent readContent(Long tenantId) {
    var technical = identityClientConfig.getTechnicalUser();
    var login =
        identityAuthentication.loginService(technical.getClientId(), technical.getClientSecret());
    if (login == null || login.accessToken() == null || login.accessToken().isBlank()) {
      throw new IllegalStateException("Service authentication unavailable");
    }
    var api = tenantServiceFactory.createControllerApi();
    securityHeaderSupplier
        .getKeycloakAndCsrfHttpHeaders(login.accessToken())
        .forEach(
            (name, values) -> values.forEach(v -> api.getApiClient().addDefaultHeader(name, v)));
    var tenant = api.getMultilingualTenantById(tenantId);
    return tenant == null ? null : tenant.getContent();
  }
}
