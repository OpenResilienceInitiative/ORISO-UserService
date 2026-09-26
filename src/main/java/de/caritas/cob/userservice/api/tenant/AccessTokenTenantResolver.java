package de.caritas.cob.userservice.api.tenant;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@AllArgsConstructor
@Component
@Slf4j
public class AccessTokenTenantResolver implements TenantResolver {

  private static final String TENANT_ID = "tenantId";

  @Override
  public Optional<Long> resolve(HttpServletRequest request) {
    return resolveTenantIdFromTokenClaims(request);
  }

  private Optional<Long> resolveTenantIdFromTokenClaims(HttpServletRequest request) {
    Map<String, Object> claimMap = getClaimMap(request);
    // Never log the full claim map: it carries the complete JWT payload (subject, roles, e-mail,
    // session ids). Only the resolved tenant is of diagnostic value here.
    var tenantId = getUserTenantIdAttribute(claimMap).filter(id -> mayClaim(id, claimMap));
    log.debug("Resolved tenantId from access token claims: {}", tenantId.orElse(null));
    return tenantId;
  }

  /** Tenant 0 switches the tenant filter off, so only the platform admin may claim it. */
  private static boolean mayClaim(Long tenantId, Map<String, Object> claimMap) {
    if (!TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return true;
    }
    var roles = realmRoles(claimMap);
    boolean platformAdmin =
        roles.contains(UserRole.AGENCY_ADMIN.getValue())
            && roles.contains(UserRole.TENANT_ADMIN.getValue());
    if (!platformAdmin) {
      log.warn("Refused tenant 0 from the access token of a caller who is no platform admin");
    }
    return platformAdmin;
  }

  private static Set<String> realmRoles(Map<String, Object> claimMap) {
    if (claimMap.get("realm_access") instanceof Map<?, ?> realmAccess
        && realmAccess.get("roles") instanceof Collection<?> roles) {
      return roles.stream().map(String::valueOf).collect(Collectors.toSet());
    }
    return Set.of();
  }

  private Optional<Long> getUserTenantIdAttribute(Map<String, Object> claimMap) {
    if (claimMap.containsKey(TENANT_ID)) {
      Object tenantIdClaim = claimMap.get(TENANT_ID);
      if (tenantIdClaim instanceof Long) {
        return Optional.of((Long) tenantIdClaim);
      }
      if (tenantIdClaim instanceof Integer) {
        return Optional.of(Long.valueOf((Integer) tenantIdClaim));
      }
      if (tenantIdClaim instanceof String) {
        try {
          return Optional.of(Long.parseLong((String) tenantIdClaim));
        } catch (NumberFormatException ex) {
          log.warn("Invalid tenantId claim value: {}", tenantIdClaim);
          return Optional.empty();
        }
      }
      log.warn("Unsupported tenantId claim type: {}", tenantIdClaim.getClass().getName());
      return Optional.empty();
    } else {
      return Optional.empty();
    }
  }

  private Map<String, Object> getClaimMap(HttpServletRequest request) {
    var principal = request.getUserPrincipal();
    if (!(principal instanceof JwtAuthenticationToken jwtToken)) {
      log.debug(
          "UserPrincipal is not a JwtAuthenticationToken (was: {}), returning empty claim map",
          principal == null ? "null" : principal.getClass().getName());
      return Collections.emptyMap();
    }
    return jwtToken.getToken().getClaims();
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
