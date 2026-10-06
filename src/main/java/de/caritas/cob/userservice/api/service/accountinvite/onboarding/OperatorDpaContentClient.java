package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
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
 * degrades to an absent contract text, which the Admin panel renders as the "text will be provided
 * by the platform operator" hint. Published text is cached for a short while because resolve is
 * called on an anonymous endpoint and each miss costs a technical-user login; a miss is never
 * cached, so publishing the operator DPA takes effect immediately.
 *
 * <p>Absence is not one state though: {@link #lookupPublishedDpa()} separates {@link
 * DpaUnavailableReason#NOT_PUBLISHED} (the operator published nothing — a content task) from {@link
 * DpaUnavailableReason#UPSTREAM_ERROR} (TenantService or the technical-user login failed — a
 * platform configuration task). Collapsing both into {@code null} once hid a server-side
 * misconfiguration for hours because the invitee was only ever told to reload the page.
 */
@Service
@Slf4j
public class OperatorDpaContentClient {

  /** How long a successfully read operator DPA is reused before the next upstream read. */
  static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  private final @NonNull TenantAdminServiceApiControllerFactory
      tenantAdminServiceApiControllerFactory;

  private final long operatorTenantId;

  private final AtomicReference<CachedContent> cache = new AtomicReference<>();

  public OperatorDpaContentClient(
      @NonNull SecurityHeaderSupplier securityHeaderSupplier,
      @NonNull IdentityAuthentication identityAuthentication,
      @NonNull IdentityClientConfig identityClientConfig,
      @NonNull TenantAdminServiceApiControllerFactory tenantAdminServiceApiControllerFactory,
      @Value("${account.invite.onboarding.operator-dpa.tenant-id:1}") long operatorTenantId) {
    this.securityHeaderSupplier = securityHeaderSupplier;
    this.identityAuthentication = identityAuthentication;
    this.identityClientConfig = identityClientConfig;
    this.tenantAdminServiceApiControllerFactory = tenantAdminServiceApiControllerFactory;
    this.operatorTenantId = operatorTenantId;
  }

  /**
   * The operator's newest published DPA: the stored language -&gt; HTML JSON map the Admin panel
   * renders read-only PLUS the activation timestamp identifying that version. {@code null} when
   * nothing is published, the lookup is disabled or TenantService could not be read.
   *
   * <p>The version travels with the content on purpose (#569): the acceptance the invitee gives is
   * recorded as a signature against exactly the version that was rendered here, never against
   * whatever happens to be current when the registration lands.
   */
  public OperatorDpa fetchPublishedDpa() {
    return lookupPublishedDpa().dpa();
  }

  /** Content-only convenience for the resolve step, which renders but does not sign. */
  public String fetchPublishedDpaContent() {
    return lookupPublishedDpa().content();
  }

  /**
   * The same read as {@link #fetchPublishedDpa()}, but it also says WHY there is no contract text:
   * {@link DpaUnavailableReason#NOT_PUBLISHED} when the operator published none (or the lookup is
   * disabled), {@link DpaUnavailableReason#UPSTREAM_ERROR} when TenantService or the technical-user
   * login failed. The reason is {@code null} exactly when a DPA is present.
   *
   * <p>Cache semantics are unchanged: only a successful read is cached, and only for {@link
   * #CACHE_TTL}. Neither unavailable outcome is cached — a freshly published DPA and a repaired
   * platform configuration both take effect on the next request.
   */
  public OperatorDpaLookup lookupPublishedDpa() {
    if (operatorTenantId <= 0) {
      return OperatorDpaLookup.unavailable(DpaUnavailableReason.NOT_PUBLISHED);
    }
    CachedContent cached = cache.get();
    if (cached != null && !cached.isExpired()) {
      return OperatorDpaLookup.found(cached.dpa());
    }
    OperatorDpaLookup lookup = readNewestPublished();
    if (lookup.dpa() != null) {
      cache.set(new CachedContent(lookup.dpa(), System.nanoTime() + CACHE_TTL.toNanos()));
    }
    return lookup;
  }

  private OperatorDpaLookup readNewestPublished() {
    List<DpaVersionDTO> versions;
    try {
      versions = createControllerApi().getDataProcessingAgreementVersions(operatorTenantId);
    } catch (RestClientException exception) {
      log.warn(
          "Could not read the operator DPA of tenant {} — the onboarding DPA step renders without"
              + " the contract text and reports {}",
          operatorTenantId,
          DpaUnavailableReason.UPSTREAM_ERROR,
          exception);
      return OperatorDpaLookup.unavailable(DpaUnavailableReason.UPSTREAM_ERROR);
    } catch (RuntimeException exception) {
      // Deliberately broad: the technical-user login happens inside createControllerApi() and
      // throws its own unchecked types (bad credentials, Keycloak unreachable, misconfigured
      // technical user). The public resolve endpoint must degrade, never 500, so ANY failure of
      // the upstream read is reported as UPSTREAM_ERROR instead of escaping.
      log.warn(
          "Could not authenticate or call TenantService for the operator DPA of tenant {} — the"
              + " onboarding DPA step renders without the contract text and reports {}",
          operatorTenantId,
          DpaUnavailableReason.UPSTREAM_ERROR,
          exception);
      return OperatorDpaLookup.unavailable(DpaUnavailableReason.UPSTREAM_ERROR);
    }
    if (versions == null || versions.isEmpty()) {
      log.warn(
          "The operator tenant {} has no published DPA — the onboarding DPA step renders without"
              + " the contract text and reports {}",
          operatorTenantId,
          DpaUnavailableReason.NOT_PUBLISHED);
      return OperatorDpaLookup.unavailable(DpaUnavailableReason.NOT_PUBLISHED);
    }
    // TenantService lists the versions newest first; skip empty snapshots defensively. Content and
    // version must come from the SAME entry, otherwise the recorded signature would name a version
    // whose wording was never shown.
    return versions.stream()
        .filter(version -> isNotBlank(version.getContent()))
        .findFirst()
        .map(
            version ->
                OperatorDpaLookup.found(
                    new OperatorDpa(version.getContent(), trimToNull(version.getActivationDate()))))
        .orElseGet(() -> OperatorDpaLookup.unavailable(DpaUnavailableReason.NOT_PUBLISHED));
  }

  private static boolean isNotBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String trimToNull(String value) {
    return isNotBlank(value) ? value.trim() : null;
  }

  /**
   * The governing operator DPA as shown to an invitee: the multilingual content snapshot and the
   * activation timestamp identifying the version ({@code null} when TenantService served none).
   */
  public record OperatorDpa(String content, String version) {}

  /**
   * Why the onboarding DPA step has no contract text to render. The two cases need different people
   * to act, so the public resolve endpoint names them instead of showing one undifferentiated
   * "reload the page" hint.
   */
  public enum DpaUnavailableReason {
    /**
     * The platform operator has published no DPA (or the lookup is switched off) — content task.
     */
    NOT_PUBLISHED,

    /** TenantService or the technical-user login failed — platform configuration task. */
    UPSTREAM_ERROR
  }

  /**
   * Outcome of an operator DPA read: either the contract ({@code reason == null}) or the reason
   * there is none ({@code dpa == null}). Never both, never neither.
   */
  public record OperatorDpaLookup(OperatorDpa dpa, DpaUnavailableReason reason) {

    static OperatorDpaLookup found(OperatorDpa dpa) {
      return new OperatorDpaLookup(dpa, null);
    }

    static OperatorDpaLookup unavailable(DpaUnavailableReason reason) {
      return new OperatorDpaLookup(null, reason);
    }

    /** The stored language -&gt; HTML map, or {@code null} when the contract is unavailable. */
    public String content() {
      return dpa == null ? null : dpa.content();
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

  private record CachedContent(OperatorDpa dpa, long expiresAtNanos) {
    boolean isExpired() {
      return System.nanoTime() - expiresAtNanos >= 0;
    }
  }
}
