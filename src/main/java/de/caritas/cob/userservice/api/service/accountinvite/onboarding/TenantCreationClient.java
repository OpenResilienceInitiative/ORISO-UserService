package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.identity.TechnicalIdentityTokenProvider;
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
 * technical user — that user must carry the {@code tenant-admin} realm role, because TenantService
 * guards {@code createTenant} with {@code AUTHORIZATION_CREATE_TENANT}.
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
  private final @NonNull TechnicalIdentityTokenProvider technicalIdentityTokenProvider;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;

  /**
   * Creates the tenant and consumes the invite's tenant-ID reservation atomically.
   *
   * @throws ConflictException when TenantService rejects the creation with 409 — the reserved ID
   *     was consumed, released or taken in the meantime (single-use link semantics upstream)
   */
  public MultilingualTenantDTO createTenant(MultilingualTenantDTO tenant) {
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
    HttpHeaders headers =
        securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(
            technicalIdentityTokenProvider.getAccessToken());
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
