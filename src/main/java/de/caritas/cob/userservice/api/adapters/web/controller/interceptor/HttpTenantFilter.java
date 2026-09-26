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
              "(/service)?/users/askers/new",
              "(/service)?/users/magic-link/(request|consume)",
              "(/service)?/users/invitelinks/[^/]+/(context|redeem)",
              "(/service)?/users/identity-suggestions",
              // TenantService's callback; DpaSignedNoticeService takes the tenant from the path.
              "(/service)?/users/tenants/[^/]+/dpa-signed-notices")
          .map(Pattern::compile)
          .toList();

  private final DefaultRequiresTenantFilterMatcher requiresTenantFilterMatcher =
      new DefaultRequiresTenantFilterMatcher();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (requiresTenantFilterMatcher.matches(request)) {
      log.debug("Trying to resolve tenant for request coming from URI {}", request.getRequestURI());
      Long tenantId;
      try {
        tenantId = tenantResolverService.resolve(request);
      } catch (AccessDeniedException denied) {
        // Thrown before ExceptionTranslationFilter, which would otherwise never turn it into a 403.
        log.warn("Refused request to {}: {}", request.getRequestURI(), denied.getMessage());
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
        return;
      }
      resolveSubdomain(tenantId);
      log.debug("Setting current tenant context to: " + tenantId);
      TenantContext.setCurrentTenant(tenantId);
      try {
        filterChain.doFilter(request, response);
      } finally {
        TenantContext.clear();
      }
    } else {
      log.debug(
          "Skipping tenant filter for request: {} as it belongs to a tenancy whitelist.",
          request.getRequestURI());
      filterChain.doFilter(request, response);
    }
  }

  private void resolveSubdomain(Long resolvedTenant) {
    if (TenantContext.TECHNICAL_TENANT_ID.equals(resolvedTenant)) {
      // Global/super-admin context has no concrete tenant entity (id 0).
      // Skip tenant-service lookup to avoid /tenant/public/id/0 404.
      TenantContext.setCurrentSubdomain("");
      return;
    }
    var currentSubdomain = tenantService.getRestrictedTenantData(resolvedTenant).getSubdomain();
    TenantContext.setCurrentSubdomain(currentSubdomain);
  }

  class DefaultRequiresTenantFilterMatcher implements RequestMatcher {
    @Override
    public boolean matches(HttpServletRequest request) {
      if (HttpMethod.OPTIONS.matches(request.getMethod())) {
        return false;
      }
      String requestUri = request.getRequestURI().toLowerCase();
      return TENANCY_FILTER_WHITELIST.stream()
          .noneMatch(route -> route.matcher(requestUri).matches());
    }
  }
}
