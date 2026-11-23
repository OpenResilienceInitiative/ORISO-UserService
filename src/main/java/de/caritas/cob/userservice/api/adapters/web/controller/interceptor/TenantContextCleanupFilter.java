package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Removes the tenant ThreadLocal once a request is finished, whatever established it.
 *
 * <p>{@link TenantContext} lives on a pooled thread, so a tenant left behind by one request
 * silently scopes whichever request picks that thread up next. Two paths used to leave one:
 *
 * <ul>
 *   <li>{@code HttpTenantFilter} cleared after the chain rather than in a {@code finally}, so a
 *       request that threw kept its tenant;
 *   <li>on a whitelisted path that filter still runs but skips tenant resolution, so it neither
 *       sets nor clears anything, while the handler behind it does establish a context — {@code
 *       CreateUserFacade#initializeTenantContextForRegistration} does exactly that for {@code
 *       /users/askers/new}, and nothing removed it afterwards.
 * </ul>
 *
 * <p>Cleaning up here rather than inside the handler is deliberate. The context has to survive the
 * whole request: {@code UserRegistrationControllerDelegate#registerUser} calls {@code
 * markAsDirectConsultant} after the facade returns, and with multitenancy on, a tenant-filtered
 * query under a cleared context matches nothing instead of failing — the session would not be found
 * and registration with a preselected counsellor would answer 500.
 *
 * <p>Deliberately unconditional, unlike {@code HttpTenantFilter}, which only exists when {@code
 * multitenancy.enabled}. The ThreadLocal can be set on any profile, so it must be cleaned on any
 * profile too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextCleanupFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    TenantContext.clear();
    try {
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
