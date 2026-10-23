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
  private final org.springframework.core.env.Environment importEnvironment;
  private final org.springframework.beans.factory.ObjectProvider<de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker> importBrokers;

  public AccountInactivityAccessFilter(AccountInactivityService lifecycle) {
    this(lifecycle, null, null);
  }

  public AccountInactivityAccessFilter(AccountInactivityService lifecycle,
      org.springframework.core.env.Environment importEnvironment,
      org.springframework.beans.factory.ObjectProvider<de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker> importBrokers) {
    this.lifecycle = lifecycle;
    this.importEnvironment = importEnvironment;
    this.importBrokers = importBrokers;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getUserPrincipal() instanceof JwtAuthenticationToken authentication) {
      var jwt = authentication.getToken();
      if (verifiedConfiguredImport(request, authentication)) {
        chain.doFilter(request, response);
        return;
      }
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

  private boolean verifiedConfiguredImport(HttpServletRequest request, JwtAuthenticationToken authentication) {
    if (importEnvironment == null || importBrokers == null
        || !"FIVE_ACTORS".equals(importEnvironment.getProperty("TASK_IDENTITY_MODEL", "LEGACY"))
        || !authentication.isAuthenticated() || !"POST".equals(request.getMethod())
        || !(request.getContextPath() + "/users/consultants/import").equals(request.getRequestURI())) return false;
    String raw = request.getHeader("Authorization");
    if (raw == null || !raw.equals("Bearer " + authentication.getToken().getTokenValue())) return false;
    try (var verified = importBrokers.getObject().verifyConsultantImportTrigger(authentication.getToken().getTokenValue())) {
      verified.requireLive();
      return true;
    } catch (RuntimeException unavailable) { return false; }
  }

  private static void addRoles(java.util.Set<String> result, Object access) {
    if (access instanceof Map<?, ?> claims && claims.get("roles") instanceof Collection<?> roles) {
      for (Object role : roles) if (role instanceof String name) result.add(name);
    }
  }
}
