package de.caritas.cob.userservice.api.tenant;

import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken;
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
    log.debug("Found tenantId in claim : " + claimMap.toString());
    return getUserTenantIdAttribute(claimMap);
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
    KeycloakSecurityContext keycloakSecContext =
        ((KeycloakAuthenticationToken) request.getUserPrincipal())
            .getAccount()
            .getKeycloakSecurityContext();
    return keycloakSecContext.getToken().getOtherClaims();
  }

  @Override
  public boolean canResolve(HttpServletRequest request) {
    return resolve(request).isPresent();
  }
}
