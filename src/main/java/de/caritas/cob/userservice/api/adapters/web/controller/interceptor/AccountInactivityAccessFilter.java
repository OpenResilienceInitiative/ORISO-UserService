package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A signed JWT remains valid after Keycloak logout, so every request also checks lifecycle state.
 */
public class AccountInactivityAccessFilter extends OncePerRequestFilter {
  private final AccountInactivityService lifecycle;

  public AccountInactivityAccessFilter(AccountInactivityService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getUserPrincipal() instanceof JwtAuthenticationToken authentication) {
      var jwt = authentication.getToken();
      String id = jwt.getSubject();
      var snapshot = lifecycle.snapshot(id);
      var roles = new java.util.HashSet<String>();
      addRoles(roles, jwt.getClaim("realm_access"));
      Object resources = jwt.getClaim("resource_access");
      if (resources instanceof Map<?, ?> clients)
        clients.values().forEach(client -> addRoles(roles, client));
      boolean technical =
          de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityIdentityRoles
              .isPureTechnical(roles);
      if (!(snapshot.isEmpty() && technical)) {
        Object authTime = jwt.getClaim("auth_time");
        Instant login = authTime instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
        if (!lifecycle.admit(id, login)) {
          response.sendError(
              HttpServletResponse.SC_FORBIDDEN, "Account access suspended or unavailable");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }

  private static void addRoles(java.util.Set<String> result, Object access) {
    if (access instanceof Map<?, ?> claims && claims.get("roles") instanceof Collection<?> roles) {
      for (Object role : roles) if (role instanceof String name) result.add(name);
    }
  }
}
