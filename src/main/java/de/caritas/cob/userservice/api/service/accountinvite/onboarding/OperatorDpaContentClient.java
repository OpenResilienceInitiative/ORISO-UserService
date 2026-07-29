package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.ApiClient;
import de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.DpaVersionDTO;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Reads the platform operator's published DPA/AVV text for the PUBLIC tenant-admin onboarding (#569
 * chain fix round 2).
 *
 * <p>The onboarding step asks the invitee to confirm the data processing agreement "on behalf of
 * their organisation" — so the wording has to be on screen. The invitee's own tenant does not exist
 * yet (the invite only reserved its ID), which is why the contract shown is the operator's own
 * published DPA: the newest version of the tenant configured as {@code
 * account.invite.onboarding.operator-dpa.tenant-id} (the platform operator's tenant, 1 by default;
 * {@code 0} or a negative value disables the lookup).
 *
 * <p>The invitee has no account while onboarding, so the read authenticates as the Keycloak
 * technical user, exactly like {@link TenantCreationClient}. That user carries the {@code
 * tenant-admin} realm role, which grants both {@code AUTHORIZATION_GET_TENANT} and {@code
 * GET_ALL_TENANTS} — TenantService therefore serves the versions of any tenant to it.
 *
 * <p>Failure isolation is deliberate: the resolve endpoint is the entry point of the whole
 * onboarding flow and must not 500 because the upstream lookup hiccuped. Every upstream failure
 * degrades to {@code null}, which the Admin panel renders as the "text will be provided by the
 * platform operator" hint. Published text is cached for a short while because resolve is called on
 * an anonymous endpoint and each miss costs a technical-user login; a miss is never cached, so
 * publishing the operator DPA takes effect immediately.
 */
@Service
@Slf4j
public class OperatorDpaContentClient {

  /** How long a successfully read operator DPA is reused before the next upstream read. */
  static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;

  private final long operatorTenantId;

  private final AtomicReference<CachedContent> cache = new AtomicReference<>();

  public OperatorDpaContentClient(
      @NonNull SecurityHeaderSupplier securityHeaderSupplier,
      @NonNull IdentityClient identityClient,
      @NonNull IdentityClientConfig identityClientConfig,
      @NonNull TenantAdminServiceApiControllerFactory tenantAdminServiceApiControllerFactory,
      @Value("${account.invite.onboarding.operator-dpa.tenant-id:1}") long operatorTenantId) {
    this.securityHeaderSupplier = securityHeaderSupplier;
    this.identityClient = identityClient;
    this.identityClientConfig = identityClientConfig;
    this.tenantAdminServiceApiControllerFactory = tenantAdminServiceApiControllerFactory;
    this.operatorTenantId = operatorTenantId;
  }

  /**
   * The operator's newest published DPA content — the stored language -&gt; HTML JSON map the Admin
   * panel renders read-only — or {@code null} when nothing is published, the lookup is disabled or
   * TenantService could not be read.
   */
  public String fetchPublishedDpaContent() {
    if (operatorTenantId <= 0) {
      return null;
    }
    CachedContent cached = cache.get();
    if (cached != null && !cached.isExpired()) {
      return cached.content();
    }
    String content = readNewestPublishedContent();
    if (content != null) {
      cache.set(new CachedContent(content, System.nanoTime() + CACHE_TTL.toNanos()));
    }
    return content;
  }

  private String readNewestPublishedContent() {
    List<DpaVersionDTO> versions;
    try {
      versions = createControllerApi().getDataProcessingAgreementVersions(operatorTenantId);
    } catch (RestClientException exception) {
      log.warn(
          "Could not read the operator DPA of tenant {} — the onboarding DPA step renders without"
              + " the contract text",
          operatorTenantId,
          exception);
      return null;
    }
    if (versions == null || versions.isEmpty()) {
      log.warn(
          "The operator tenant {} has no published DPA — the onboarding DPA step renders without"
              + " the contract text",
          operatorTenantId);
      return null;
    }
    // TenantService lists the versions newest first; skip empty snapshots defensively.
    return versions.stream()
        .map(DpaVersionDTO::getContent)
        .filter(content -> content != null && !content.trim().isEmpty())
        .findFirst()
        .orElse(null);
  }

  private TenantControllerApi createControllerApi() {
    var controllerApi = tenantAdminServiceApiControllerFactory.createControllerApi();
    addTechnicalUserHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addTechnicalUserHeaders(ApiClient apiClient) {
    var techUser = identityClientConfig.getTechnicalUser();
    var keycloakLogin = identityClient.loginUser(techUser.getUsername(), techUser.getPassword());
    HttpHeaders headers =
        securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(keycloakLogin.getAccessToken());
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }

  private record CachedContent(String content, long expiresAtNanos) {
    boolean isExpired() {
      return System.nanoTime() - expiresAtNanos >= 0;
    }
  }
}
