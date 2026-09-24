package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpTenantFilterTest {

  @InjectMocks HttpTenantFilter httpTenantFilter;

  @Mock private TenantResolverService tenantResolverService;

  @Mock private TenantService tenantService;

  @Mock HttpServletRequest request;

  @Mock HttpServletResponse response;

  @Mock FilterChain filterChain;

  @Test
  void doFilterInternal_Should_NotApply_When_RequestBelongsToTenancyWhiteList()
      throws ServletException, IOException {
    // given
    Mockito.when(request.getRequestURI()).thenReturn("/actuator/health/liveness");

    // when
    httpTenantFilter.doFilterInternal(request, response, filterChain);

    // then
    Mockito.verifyNoInteractions(tenantResolverService);
  }

  @Test
  void matrixRtcPolicyEndpointDoesNotRequireBrowserTenantContext()
      throws ServletException, IOException {
    Mockito.when(request.getRequestURI()).thenReturn("/internal/matrixrtc/call-policy");

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verifyNoInteractions(tenantResolverService, tenantService);
    Mockito.verify(filterChain).doFilter(request, response);
  }

  @Test
  void dpaSignedNoticeCallbackDoesNotRequireBrowserTenantContext()
      throws ServletException, IOException {
    // Headerless machine callback; the tenant travels in the path.
    Mockito.when(request.getRequestURI()).thenReturn("/users/tenants/42/dpa-signed-notices");

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verifyNoInteractions(tenantResolverService, tenantService);
    Mockito.verify(filterChain).doFilter(request, response);
  }

  @Test
  void dpaSignedNoticeCallbackIsExemptUnderTheServicePrefixToo()
      throws ServletException, IOException {
    Mockito.when(request.getRequestURI())
        .thenReturn("/service/users/tenants/42/dpa-signed-notices");

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verifyNoInteractions(tenantResolverService, tenantService);
    Mockito.verify(filterChain).doFilter(request, response);
  }

  @Test
  void siblingTenantRouteStillRequiresTenantContext() throws ServletException, IOException {
    Mockito.when(request.getRequestURI()).thenReturn("/users/tenants/42/dpa-signed-notices/extra");
    Mockito.when(tenantResolverService.resolve(request)).thenReturn(1L);
    Mockito.when(tenantService.getRestrictedTenantData(1L)).thenReturn(new RestrictedTenantDTO());

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verify(tenantResolverService).resolve(request);
    Mockito.verify(filterChain).doFilter(request, response);
  }

  @Test
  void siblingMatrixRtcEndpointStillRequiresTenantContext() throws ServletException, IOException {
    Mockito.when(request.getRequestURI()).thenReturn("/internal/matrixrtc/future-endpoint");
    Mockito.when(tenantResolverService.resolve(request)).thenReturn(1L);
    Mockito.when(tenantService.getRestrictedTenantData(1L)).thenReturn(new RestrictedTenantDTO());

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verify(tenantResolverService).resolve(request);
    Mockito.verify(filterChain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_Should_Apply_When_DoesNotBelongBelongsToTenancyWhiteList()
      throws ServletException, IOException {

    // given
    Mockito.when(request.getRequestURI()).thenReturn("/users/1");
    Mockito.when(tenantResolverService.resolve(request)).thenReturn(1L);
    Mockito.when(tenantService.getRestrictedTenantData(1L)).thenReturn(new RestrictedTenantDTO());

    // when
    httpTenantFilter.doFilterInternal(request, response, filterChain);

    // then
    Mockito.verify(tenantResolverService).resolve(request);
  }

  @Test
  void routeThatOnlyContainsAWhitelistedPathStillRequiresTenantContext()
      throws ServletException, IOException {
    Mockito.when(request.getRequestURI()).thenReturn("/useradmin/users/askers/new");
    Mockito.when(tenantResolverService.resolve(request)).thenReturn(1L);
    Mockito.when(tenantService.getRestrictedTenantData(1L)).thenReturn(new RestrictedTenantDTO());

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verify(tenantResolverService).resolve(request);
  }

  @Test
  void whitelistedRoutesMatchUnderTheServicePrefix() throws ServletException, IOException {
    Mockito.when(request.getRequestURI()).thenReturn("/service/users/magic-link/consume");

    httpTenantFilter.doFilterInternal(request, response, filterChain);

    Mockito.verifyNoInteractions(tenantResolverService, tenantService);
  }

  @Test
  void tenantIsClearedWhenTheRequestFails() throws ServletException, IOException {
    Mockito.when(request.getRequestURI()).thenReturn("/users/1");
    Mockito.when(tenantResolverService.resolve(request)).thenReturn(1L);
    Mockito.when(tenantService.getRestrictedTenantData(1L)).thenReturn(new RestrictedTenantDTO());
    Mockito.doThrow(new ServletException("boom")).when(filterChain).doFilter(request, response);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> httpTenantFilter.doFilterInternal(request, response, filterChain))
        .isInstanceOf(ServletException.class);

    org.assertj.core.api.Assertions.assertThat(
            de.caritas.cob.userservice.api.tenant.TenantContext.getCurrentTenant())
        .isNull();
  }
}
