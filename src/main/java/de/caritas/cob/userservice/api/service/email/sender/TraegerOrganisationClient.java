package de.caritas.cob.userservice.api.service.email.sender;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantDTO;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A Träger's own organisation data, entered at onboarding or under Träger → Allgemein: its full
 * legal name (else its display name), its postal address, and its contact e-mail and phone as one
 * contact line. What the Träger did not enter stays empty, and the platform owner's value applies.
 *
 * <p>The address is only in the admin view of a tenant ({@code GET /tenant/{id}}), which needs the
 * {@code tenant-admin} role; mails are also sent where no such user is logged in, so the read
 * authenticates as the technical user, like {@code OperatorDpaContentClient}. Every failure
 * degrades to "no Träger data", which leaves the platform owner's block in the footer.
 */
@Slf4j
@Component
public class TraegerOrganisationClient {

  static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final SecurityHeaderSupplier securityHeaderSupplier;
  private final IdentityAuthentication identityAuthentication;
  private final IdentityClientConfig identityClientConfig;
  private final TenantAdminServiceApiControllerFactory controllerFactory;
  private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

  public TraegerOrganisationClient(
      @NonNull SecurityHeaderSupplier securityHeaderSupplier,
      @NonNull IdentityAuthentication identityAuthentication,
      @NonNull IdentityClientConfig identityClientConfig,
      @NonNull TenantAdminServiceApiControllerFactory controllerFactory) {
    this.securityHeaderSupplier = securityHeaderSupplier;
    this.identityAuthentication = identityAuthentication;
    this.identityClientConfig = identityClientConfig;
    this.controllerFactory = controllerFactory;
  }

  /**
   * @param tenantId the Träger; {@code null} and the platform tenant have no Träger data
   */
  public Optional<SenderOrganisation> fetch(Long tenantId) {
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return Optional.empty();
    }
    Cached cached = cache.get(tenantId);
    if (cached != null && System.nanoTime() - cached.expiresAtNanos() < 0) {
      return Optional.of(cached.organisation());
    }
    try {
      TenantDTO tenant = technicalUserApi().getTenantById(tenantId);
      SenderOrganisation organisation =
          tenant == null
              ? SenderOrganisation.NONE
              : new SenderOrganisation(
                  isBlank(tenant.getLegalName()) ? tenant.getName() : tenant.getLegalName(),
                  tenant.getAddress(),
                  SenderOrganisation.contactLine(
                      tenant.getContactEmail(), tenant.getContactPhone()));
      if (organisation.isEmpty()) {
        return Optional.empty();
      }
      cache.put(tenantId, new Cached(organisation, System.nanoTime() + CACHE_TTL.toNanos()));
      return Optional.of(organisation);
    } catch (HttpClientErrorException.NotFound reservedOnly) {
      // A tenant-admin invite goes out before its tenant exists.
      return Optional.empty();
    } catch (RuntimeException exception) {
      log.warn(
          "Could not read the organisation data of tenant {} — the mail footer uses the platform"
              + " owner's",
          tenantId,
          exception);
      return Optional.empty();
    }
  }

  private TenantControllerApi technicalUserApi() {
    var api = controllerFactory.createControllerApi();
    var technicalUser = identityClientConfig.getTechnicalUser();
    var login =
        identityAuthentication.login(technicalUser.getUsername(), technicalUser.getPassword());
    securityHeaderSupplier
        .getKeycloakAndCsrfHttpHeaders(login.accessToken())
        .forEach((key, value) -> api.getApiClient().addDefaultHeader(key, value.iterator().next()));
    return api;
  }

  private record Cached(SenderOrganisation organisation, long expiresAtNanos) {}
}
