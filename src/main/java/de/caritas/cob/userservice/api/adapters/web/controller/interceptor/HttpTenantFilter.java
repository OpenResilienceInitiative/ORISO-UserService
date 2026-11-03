package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Sets tenantId for current thread needed for tenant feature. */
@Component
@ConditionalOnExpression("${multitenancy.enabled:true}")
@RequiredArgsConstructor
@Slf4j
public class HttpTenantFilter extends OncePerRequestFilter {

  private final @Nullable TenantResolverService tenantResolverService;

  private final @Nullable TenantService tenantService;

  // Public routes that carry no tenant, matched exactly: a substring match would let any URI
  // containing one of them skip tenant resolution.
  private static final List<Pattern> TENANCY_FILTER_WHITELIST =
      Stream.of(
              "/actuator/health(/.*)?",
              "/actuator/loggers(/.*)?",
              "/swagger-ui\\.html",
              "/favicon\\.ico",
              "/internal/matrixrtc/call-policy",
              "/internal/matrix/group-join-policy",
              "/internal/matrixrtc/media-access",
              "(/service)?/users/askers/new",
              "(/service)?/users/magic-link/(request|consume)",
              "(/service)?/users/password-reset/(request|confirm)",
              // Token-owned routes enforce operational admission before creating ordinary work;
              // read, existing-account setup and second-factor resume must remain possible.
              "(/service)?/users/account-invites/[^/]+(/(accept|setup|onboarding(/(register|two-factor(/email)?|dpa-forward|dpa-mail-preview))?))?",
              "(/service)?/users/invitelinks/[^/]+/(context|redeem)",
              "(/service)?/users/identity-suggestions",
              // TenantService's callback; DpaSignedNoticeService takes the tenant from the path.
              "(/service)?/users/tenants/[^/]+/dpa-signed-notices",
              // A Träger admin reports the deletion of its own Träger after it is gone, so its
              // tenant can no longer be admitted. The controller checks tenant and scope itself.
              "(/service)?/users/deletion-audit-logs")
          .map(Pattern::compile)
          .toList();

  private final DefaultRequiresTenantFilterMatcher requiresTenantFilterMatcher =
      new DefaultRequiresTenantFilterMatcher();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (requiresTenantFilterMatcher.matches(request)) {
      // Never log the path: invite routes carry the raw token, the invitee's only credential.
      log.debug("Resolving the tenant for a {} request", request.getMethod());
      // Every exit clears the context, so a pooled thread never keeps a tenant from before.
      try {
        Long tenantId;
        try {
          tenantId = tenantResolverService.resolve(request);
        } catch (AccessDeniedException denied) {
          // Thrown before ExceptionTranslationFilter, which would otherwise never make it a 403.
          log.warn("Refused a {} request: {}", request.getMethod(), denied.getMessage());
          response.sendError(HttpServletResponse.SC_FORBIDDEN);
          return;
        }
        try {
          if (!resolveSubdomain(tenantId, request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant operationally inactive");
            return;
          }
        } catch (org.springframework.web.client.RestClientException unavailable) {
          response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Tenant status unavailable");
          return;
        }
        log.debug("Setting current tenant context to: " + tenantId);
        TenantContext.setCurrentTenant(tenantId);
        filterChain.doFilter(request, response);
      } finally {
        TenantContext.clear();
      }
    } else {
      log.debug("Skipping tenant resolution for a whitelisted {} request", request.getMethod());
      filterChain.doFilter(request, response);
    }
  }

  private boolean resolveSubdomain(Long resolvedTenant, HttpServletRequest request) {
    if (TenantContext.TECHNICAL_TENANT_ID.equals(resolvedTenant)) {
      // Global/super-admin context has no concrete tenant entity (id 0).
      // Skip tenant-service lookup to avoid /tenant/public/id/0 404.
      TenantContext.setCurrentSubdomain("");
      return true;
    }
    // Admission must see a recent pause; the branding cache may outlive the operational update.
    var tenant = tenantService.getRestrictedTenantDataFresh(resolvedTenant);
    if (tenant == null)
      throw new org.springframework.web.client.RestClientException("Missing tenant metadata");
    TenantContext.setCurrentSubdomain(tenant.getSubdomain());
    String path = applicationPath(request);
    boolean management = path.startsWith("/useradmin/") || path.startsWith("/service/useradmin/");
    // Existing backend authorisation still governs the management route; the pause adds no rights.
    boolean bootstrap =
        org.springframework.http.HttpMethod.GET.matches(request.getMethod())
            && (path.equals("/users/data") || path.equals("/service/users/data"))
            && request.getUserPrincipal()
                instanceof
                org.springframework.security.oauth2.server.resource.authentication
                            .JwtAuthenticationToken
                        auth
            && de.caritas.cob.userservice.api.config.auth.KeycloakRoles.of(
                    auth.getToken().getClaims())
                .stream()
                .anyMatch(
                    role ->
                        java.util.Set.of(
                                "tenant-admin",
                                "single-tenant-admin",
                                "restricted-agency-admin",
                                "user-admin")
                            .contains(role));
    return management || bootstrap || !Boolean.FALSE.equals(tenant.getActive());
  }

  private static String applicationPath(HttpServletRequest request) {
    String context = request.getContextPath();
    String uri = request.getRequestURI();
    return context == null || context.isEmpty() ? uri : uri.substring(context.length());
  }

  class DefaultRequiresTenantFilterMatcher implements RequestMatcher {
    @Override
    public boolean matches(HttpServletRequest request) {
      if (HttpMethod.OPTIONS.matches(request.getMethod())) {
        return false;
      }
      String requestUri = applicationPath(request).toLowerCase(java.util.Locale.ROOT);
      return TENANCY_FILTER_WHITELIST.stream()
          .noneMatch(route -> route.matcher(requestUri).matches());
    }
  }
}
