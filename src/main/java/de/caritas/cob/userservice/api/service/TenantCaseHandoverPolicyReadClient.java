package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.TenantPermissionPolicies;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/** Authenticated provider read usable from both request and scheduled refresh contexts. */
@Service
@RequiredArgsConstructor
public class TenantCaseHandoverPolicyReadClient {
  private final TenantAdminServiceApiControllerFactory tenantServiceFactory;
  private final IdentityAuthentication identityAuthentication;
  private final IdentityClientConfig identityClientConfig;
  private final SecurityHeaderSupplier securityHeaderSupplier;

  public TenantPermissionPolicies getTenantPermissionPolicies(Long tenantId) {
    if (tenantId == null || tenantId <= 0)
      throw new IllegalArgumentException("Invalid policy tenant");
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
      return api.getTenantPermissionPolicies(tenantId);
    } catch (RuntimeException exception) {
      // Neither downstream response bodies nor identity credentials may enter fallback logs.
      throw new IllegalStateException("Tenant policy read failed: " + failureSummary(exception));
    } finally {
      if (login != null && login.refreshToken() != null && !login.refreshToken().isBlank()) {
        try {
          identityAuthentication.logout(login.refreshToken(), login.accessToken());
        } catch (RuntimeException ignored) {
          // The read outcome remains authoritative; never expose identity provider replies.
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
