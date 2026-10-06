package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantPermissionPolicies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * TenantService permission-policy access. The last-known-good cache refresh (scheduled or on a
 * cache miss) reads as the technical service identity; everything an admin triggers — the
 * read-modify-write of the Case Handover policy — runs with that admin's own token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantCaseHandoverPolicyReadClient {
  private final TenantAdminServiceApiControllerFactory tenantServiceFactory;
  private final IdentityAuthentication identityAuthentication;
  private final IdentityClientConfig identityClientConfig;
  private final SecurityHeaderSupplier securityHeaderSupplier;

  /** Cache-refresh read as the technical service identity; never used for admin writes. */
  public TenantPermissionPolicies getTenantPermissionPolicies(Long tenantId) {
    requireValidTenant(tenantId);
    return withTechnicalSession(
        api -> api.getTenantPermissionPolicies(tenantId), "tenant policy read");
  }

  /**
   * Admin-triggered read that precedes a policy write. Runs with the calling admin's own token so
   * TenantService applies that admin's tenant rights, never the service identity's.
   */
  public TenantPermissionPolicies getTenantPermissionPoliciesAsCaller(Long tenantId) {
    requireValidTenant(tenantId);
    return withCallerSession(
        api -> api.getTenantPermissionPolicies(tenantId), "tenant policy read");
  }

  /**
   * Admin-triggered policy write. Forwards the calling admin's own token; TenantService decides
   * whether that admin may change this tenant's settings. There is deliberately no fallback to the
   * technical user (ORISO-Helm#367).
   */
  public TenantPermissionPolicies updateTenantPermissionPoliciesAsCaller(
      Long tenantId, TenantPermissionPolicies policies) {
    requireValidTenant(tenantId);
    return withCallerSession(
        api -> api.updateTenantPermissionPolicies(tenantId, policies), "tenant policy update");
  }

  private static void requireValidTenant(Long tenantId) {
    if (tenantId == null || tenantId <= 0)
      throw new IllegalArgumentException("Invalid policy tenant");
  }

  private TenantPermissionPolicies withCallerSession(
      java.util.function.Function<
              de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi,
              TenantPermissionPolicies>
          operation,
      String operationName) {
    var headers = securityHeaderSupplier.getCallerKeycloakAndCsrfHttpHeaders();
    var api = tenantServiceFactory.createControllerApi();
    headers.forEach(
        (name, values) ->
            values.forEach(value -> api.getApiClient().addDefaultHeader(name, value)));
    try {
      return operation.apply(api);
    } catch (RestClientResponseException exception) {
      int status = exception.getStatusCode().value();
      if (status == 401 || status == 403) {
        // The callee refused this admin; surface it as the admin's own missing right.
        throw new ForbiddenException(operationName + " was not permitted for the caller");
      }
      throw new IllegalStateException(operationName + " failed: " + failureSummary(exception));
    } catch (RuntimeException exception) {
      // Downstream response bodies may not enter logs or API errors.
      throw new IllegalStateException(operationName + " failed: " + failureSummary(exception));
    }
  }

  private TenantPermissionPolicies withTechnicalSession(
      java.util.function.Function<
              de.caritas.cob.userservice.tenantadminservice.generated.web.TenantControllerApi,
              TenantPermissionPolicies>
          operation,
      String operationName) {
    IdentityLogin login = null;
    try {
      var technical = identityClientConfig.getTechnicalUser();
      login = identityAuthentication.login(technical.getUsername(), technical.getPassword());
      if (login == null || login.accessToken() == null || login.accessToken().isBlank())
        throw new IllegalStateException("Service authentication unavailable");
      var api = tenantServiceFactory.createControllerApi();
      securityHeaderSupplier
          .getKeycloakAndCsrfHttpHeaders(login.accessToken())
          .forEach(
              (name, values) ->
                  values.forEach(value -> api.getApiClient().addDefaultHeader(name, value)));
      return operation.apply(api);
    } catch (RuntimeException exception) {
      // Neither downstream response bodies nor identity credentials may enter fallback logs.
      throw new IllegalStateException(operationName + " failed: " + failureSummary(exception));
    } finally {
      if (login != null && login.refreshToken() != null && !login.refreshToken().isBlank()) {
        try {
          identityAuthentication.logout(login.refreshToken(), login.accessToken());
        } catch (RuntimeException exception) {
          // The read outcome remains authoritative; never expose identity provider replies.
          log.warn(
              "Technical-user logout failed after tenant policy read: {}",
              failureSummary(exception));
        }
      }
    }
  }

  static String failureSummary(RuntimeException exception) {
    return exception.getClass().getSimpleName()
        + (exception instanceof RestClientResponseException response
            ? " HTTP " + response.getStatusCode().value()
            : "");
  }
}
