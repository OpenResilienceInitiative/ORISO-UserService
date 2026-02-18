package de.caritas.cob.userservice.api.tenant;

import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import jakarta.servlet.http.HttpServletRequest;

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
    log.debug("Found tenantId in claim : " + claimMap.toString());
    return getUserTenantIdAttribute(claimMap);
  }

  private Optional<Long> getUserTenantIdAttribute(Map<String, Object> claimMap) {
    if (claimMap.containsKey(TENANT_ID)) {
      Integer tenantId = (Integer) claimMap.get(TENANT_ID);
      return Optional.of(Long.valueOf(tenantId));
    } else {
      return Optional.empty();
    }
  }

  private Map<String, Object> getClaimMap(HttpServletRequest request) {
    if (request.getUserPrincipal() instanceof JwtAuthenticationToken jwtAuth) {
      return jwtAuth.getToken().getClaims();
    }
    return Map.of();
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
