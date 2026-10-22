package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.RoutineTaskAuthentication;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private ReservedTenantBootstrap reservedTenantBootstrap;

  /** Exact protected held operation; the private SQL claim is checked before authentication. */
  public VerifiedTenant createReservedTenant(
      de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance
              .AcceptanceClaim
          claim,
      MultilingualTenantDTO tenant) {
    if (reservedTenantBootstrap == null)
      throw new IllegalStateException("Protected Tenant creation is unavailable");
    var reply = reservedTenantBootstrap.create(claim, tenant);
    return new VerifiedTenant(
        reply.id(),
        reply.receipt(),
        reply.tenant(),
        reply.claim(),
        reply.attempt(),
        reply.execution());
  }

  /** Authenticated result ownership only; never a generic tenant creation grant. */
  public static final class VerifiedTenant {
    private final long id;
    private final String proof;
    private final MultilingualTenantDTO value;
    private final java.util.UUID claim;
    private final java.util.UUID attempt;
    private final java.util.UUID execution;

    private VerifiedTenant(
        long id,
        String proof,
        MultilingualTenantDTO value,
        java.util.UUID claim,
        java.util.UUID attempt,
        java.util.UUID execution) {
      this.id = id;
      this.proof = proof;
      this.value = ReservedTenantBootstrap.copy(value);
      this.claim = claim;
      this.attempt = attempt;
      this.execution = execution;
    }

    public long tenantId() {
      return id;
    }

    public String receipt() {
      return proof;
    }

    public MultilingualTenantDTO tenant() {
      return ReservedTenantBootstrap.copy(value);
    }

    public java.util.UUID claimId() {
      return claim;
    }

    public java.util.UUID attemptId() {
      return attempt;
    }

    public java.util.UUID executionId() {
      return execution;
    }

    @Override
    public String toString() {
      return "VerifiedHeldTenant[redacted]";
    }
  }

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
    String token =
        RoutineTaskAuthentication.accessToken(
            TaskPurpose.CONFIG_WIZARD, identityClientConfig, identityAuthentication);
    HttpHeaders headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(token);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
