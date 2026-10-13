package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Existing bearer sessions cannot bypass an explicit pause on the selected identity. */
@lombok.extern.slf4j.Slf4j
public class ManagedAccountPauseAccessFilter extends OncePerRequestFilter {
  private final ManagedAccountStatusService accounts;

  public ManagedAccountPauseAccessFilter(ManagedAccountStatusService accounts) {
    this.accounts = accounts;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getUserPrincipal() instanceof JwtAuthenticationToken authentication) {
      try {
        if (accounts.isManuallyPaused(authentication.getToken().getSubject())) {
          response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account manually paused");
          return;
        }
      } catch (RuntimeException unavailable) {
        log.warn(
            "Managed account admission unavailable: {}", unavailable.getClass().getSimpleName());
        response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Account admission unavailable");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
