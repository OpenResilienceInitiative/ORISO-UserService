package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Server-to-server tenant creation for the PUBLIC tenant-admin onboarding (#569 chain fix). The
 * invitee has no account while onboarding, so the call authenticates as the configured Keycloak
 * technical user. Target model (ORISO-Helm#367): that identity holds no {@code tenant-admin} role;
 * TenantService lets it create a tenant only by consuming a valid reservation token, so this client
 * never sends a creation without the reserved ID and its token.
 *
 * <p>Reservation consumption is atomic on the TenantService side: the tenant is created with the
 * invite's reserved ID plus the matching {@code tenantIdReservationToken}; a reserved ID without
 * the matching token is rejected with 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantCreationClient {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;

  /**
   * Creates the tenant and consumes the invite's tenant-ID reservation atomically.
   *
   * @throws ConflictException when TenantService rejects the creation with 409 — the reserved ID
   *     was consumed, released or taken in the meantime (single-use link semantics upstream)
   */
  public MultilingualTenantDTO createTenant(MultilingualTenantDTO tenant) {
    // The service identity may only create a tenant by consuming the invite's reservation
    // (ORISO-Helm#367). Fail closed here instead of letting a creation without the pair reach
    // TenantService under the technical user.
    if (tenant == null
        || tenant.getId() == null
        || tenant.getTenantIdReservationToken() == null
        || tenant.getTenantIdReservationToken().isBlank()) {
      throw new IllegalStateException(
          "Tenant creation requires the invite's reserved tenant ID and its reservation token");
    }
    try {
      return createControllerApi().createTenant(tenant);
    } catch (HttpClientErrorException.Conflict exception) {
      throw new ConflictException(
          "Tenant creation conflicted — the reserved tenant ID is no longer consumable");
    }
  }

  private TenantControllerApi createControllerApi() {
    var controllerApi = tenantAdminServiceApiControllerFactory.createControllerApi();
    addTechnicalUserHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addTechnicalUserHeaders(ApiClient apiClient) {
    var techUser = identityClientConfig.getTechnicalUser();
    var identityLogin =
        identityAuthentication.login(techUser.getUsername(), techUser.getPassword());
    HttpHeaders headers =
        securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(identityLogin.accessToken());
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
