package de.caritas.cob.userservice.api.tenant;

import static de.caritas.cob.userservice.api.config.auth.UserRole.TECHNICAL;
import static de.caritas.cob.userservice.api.config.auth.UserRole.TENANT_ADMIN;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import jakarta.servlet.http.HttpServletRequest;

@Component
public class TechnicalOrSuperAdminUserTenantResolver implements TenantResolver {

  @Override
  public Optional<Long> resolve(HttpServletRequest request) {
    return isTechnicalOrTenantSuperAdminUserRole(request) ? Optional.of(0L) : Optional.empty();
  }

  private boolean isTechnicalOrTenantSuperAdminUserRole(HttpServletRequest request) {
    return containsAnyRole(request, TECHNICAL.getValue(), TENANT_ADMIN.getValue());
  }

  private boolean containsAnyRole(HttpServletRequest request, String... expectedRoles) {
    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken jwtAuth)) {
      return false;
    }

    Set<String> roles = extractRealmRoles(jwtAuth.getToken().getClaims());
    return containsAny(roles, expectedRoles);
  }

  private boolean containsAny(Set<String> roles, String... expectedRoles) {
    return Arrays.stream(expectedRoles).anyMatch(roles::contains);
  }

  private Set<String> extractRealmRoles(Map<String, Object> claims) {
    Object realmAccess = claims.get("realm_access");
    if (realmAccess instanceof Map<?, ?> realmAccessMap) {
      Object roles = realmAccessMap.get("roles");
      if (roles instanceof Collection<?> rolesCollection) {
        return rolesCollection.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(java.util.stream.Collectors.toSet());
      }
    }
    return Set.of();
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
