package de.caritas.cob.userservice.api.tenant;

import static de.caritas.cob.userservice.api.config.auth.UserRole.TECHNICAL;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken;
import org.keycloak.representations.AccessToken;
import org.springframework.stereotype.Component;

@Component
public class TechnicalOrSuperAdminUserTenantResolver implements TenantResolver {

  @Override
  public Optional<Long> resolve(HttpServletRequest request) {
    return isTechnicalOrGlobalTenantAdmin(request) ? Optional.of(0L) : Optional.empty();
  }

  private boolean isTechnicalOrGlobalTenantAdmin(HttpServletRequest request) {
    AccessToken token = getAccessToken(request);
    // Only technical role should force global tenant context.
    // Super-admin is still resolved as tenant 0 through AccessTokenTenantResolver claim parsing.
    return containsRole(token, TECHNICAL.getValue());
  }

  private AccessToken getAccessToken(HttpServletRequest request) {
    return ((KeycloakAuthenticationToken) request.getUserPrincipal())
        .getAccount()
        .getKeycloakSecurityContext()
        .getToken();
  }

  private boolean containsRole(AccessToken token, String expectedRole) {
    if (hasRoles(token)) {
      Set<String> roles = token.getRealmAccess().getRoles();
      return roles.contains(expectedRole);
    } else {
      return false;
    }
  }

  private boolean hasRoles(AccessToken accessToken) {
    return accessToken.getRealmAccess() != null && accessToken.getRealmAccess().getRoles() != null;
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
