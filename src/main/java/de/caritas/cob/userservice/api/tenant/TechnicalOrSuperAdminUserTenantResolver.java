package de.caritas.cob.userservice.api.tenant;

import static de.caritas.cob.userservice.api.config.auth.UserRole.TECHNICAL;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class TechnicalOrSuperAdminUserTenantResolver implements TenantResolver {

  @Override
  public Optional<Long> resolve(HttpServletRequest request) {
    return isTechnicalOrGlobalTenantAdmin(request) ? Optional.of(0L) : Optional.empty();
  }

  private boolean isTechnicalOrGlobalTenantAdmin(HttpServletRequest request) {
    Jwt token = getAccessToken(request);
    // Only technical role should force global tenant context.
    // Super-admin is still resolved as tenant 0 through AccessTokenTenantResolver claim parsing.
    return containsRole(token, TECHNICAL.getValue());
  }

  private Jwt getAccessToken(HttpServletRequest request) {
    return ((JwtAuthenticationToken) request.getUserPrincipal()).getToken();
  }

  private boolean containsRole(Jwt token, String expectedRole) {
    return extractRealmRoles(token).contains(expectedRole);
  }

  @SuppressWarnings("unchecked")
  private Set<String> extractRealmRoles(Jwt token) {
    Object realmAccess = token.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      Object rolesClaim = realmAccessMap.get("roles");
      if (rolesClaim instanceof Collection<?> roles) {
        return roles.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
      }
    }
    return Set.of();
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
