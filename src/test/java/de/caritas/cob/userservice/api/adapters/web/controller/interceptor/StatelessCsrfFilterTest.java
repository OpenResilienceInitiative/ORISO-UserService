package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties.ConfigProperty;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties.Whitelist;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.security.web.access.AccessDeniedHandler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class StatelessCsrfFilterTest {

  private static final String CSRF_HEADER = "csrfHeader";
  private static final String CSRF_COOKIE = "csrfCookie";
  private static final String CSRF_WHITELIST_COOKIE = "csrfWhitelistHeader";
  private static final String ADMIN_URI_ON_WHITE_LIST = "/useradmin";

  private StatelessCsrfFilter csrfFilter;

  @Mock private CsrfSecurityProperties csrfSecurityProperties;

  @Mock private HttpServletRequest request;

  @Mock private HttpServletResponse response;

  @Mock private FilterChain filterChain;

  @Mock private AccessDeniedHandler accessDeniedHandler;

  @BeforeEach
  public void setup() {
    ConfigProperty cookieProperty = new ConfigProperty();
    cookieProperty.setProperty(CSRF_COOKIE);
    ConfigProperty headerProperty = new ConfigProperty();
    headerProperty.setProperty(CSRF_HEADER);
    ConfigProperty whitelistProperty = new ConfigProperty();
    whitelistProperty.setProperty(CSRF_WHITELIST_COOKIE);

    Whitelist whitelist = new Whitelist();
    whitelist.setAdminUris(new String[] {ADMIN_URI_ON_WHITE_LIST});
    whitelist.setConfigUris(new String[] {});
    whitelist.setHeader(whitelistProperty);

    when(csrfSecurityProperties.getHeader()).thenReturn(headerProperty);
    when(csrfSecurityProperties.getCookie()).thenReturn(cookieProperty);
    when(csrfSecurityProperties.getWhitelist()).thenReturn(whitelist);
    csrfFilter = new StatelessCsrfFilter(csrfSecurityProperties);

    setField(csrfFilter, "accessDeniedHandler", accessDeniedHandler);
  }

  @Test
  public void doFilterInternal_Should_executeFilterChain_When_requestMethodIsAllowed()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("uri");
    when(request.getMethod()).thenReturn("OPTIONS");

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.filterChain, times(1)).doFilter(request, response);
  }

  @Test
  public void doFilterInternal_Should_executeFilterChain_When_requestUriIsInWhiteList()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn(ADMIN_URI_ON_WHITE_LIST);

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void accountInviteAcceptanceShouldNotRequireCsrfBeforeLogin()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("/service/users/account-invites/emailed-token/accept");
    when(request.getMethod()).thenReturn("POST");

    csrfFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verifyNoMoreInteractions(accessDeniedHandler);
  }

  @Test
  void dpaSignedNoticeHintShouldNotRequireCsrf() throws IOException, ServletException {
    // TenantService posts this data-free hint server-to-server after a forwarded DPA signature
    // lands; it carries neither the browser CSRF cookie nor the header pair. Without the
    // exemption the hint dies here and the forwarder's promised notification mail is never sent
    // (found in E2E run e2e-20260818-2024: "CSRF rejected request:
    // uri=/users/tenants/30/dpa-signed-notices").
    when(request.getRequestURI()).thenReturn("/service/users/tenants/30/dpa-signed-notices");
    when(request.getMethod()).thenReturn("POST");

    csrfFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verifyNoMoreInteractions(accessDeniedHandler);
  }

  @Test
  void otherTenantSubresourcesStillRequireCsrf() throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("/service/users/tenants/30/dpa-signed-notices-extra");
    when(request.getMethod()).thenReturn("POST");
    when(request.getCookies()).thenReturn(null);

    csrfFilter.doFilterInternal(request, response, filterChain);

    verify(accessDeniedHandler, times(1)).handle(any(), any(), any());
  }

  @Test
  public void doFilterInternal_Should_executeFilterChain_When_requestHasCsrfWhitelistHeader()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("uri");
    when(request.getHeader(CSRF_WHITELIST_COOKIE)).thenReturn("whitelisted");

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.filterChain, times(1)).doFilter(request, response);
  }

  @Test
  public void doFilterInternal_Should_executeFilterChain_ForExactInternalMatrixRtcPolicyEndpoint()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("/internal/matrixrtc/call-policy");
    when(request.getMethod()).thenReturn("POST");

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.filterChain, times(1)).doFilter(request, response);
  }

  @Test
  public void doFilterInternal_Should_requireCsrf_ForOtherInternalMatrixRtcEndpoints()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("/internal/matrixrtc/call-policy-extra");
    when(request.getMethod()).thenReturn("POST");

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.accessDeniedHandler, times(1)).handle(any(), any(), any());
    verifyNoMoreInteractions(this.filterChain);
  }

  @Test
  public void doFilterInternal_Should_executeFilterChain_When_requestCsrfHeaderAndCookieAreEqual()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("uri");
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader(CSRF_HEADER)).thenReturn("csrfTokenValue");
    Cookie[] cookies = {new Cookie(CSRF_COOKIE, "csrfTokenValue")};
    when(request.getCookies()).thenReturn(cookies);

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.filterChain, times(1)).doFilter(request, response);
  }

  @Test
  public void doFilterInternal_Should_callAccessDeniedHandler_When_csrfHeaderIsNull()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("uri");
    when(request.getMethod()).thenReturn("POST");
    Cookie[] cookies = {new Cookie(CSRF_COOKIE, "csrfTokenValue")};
    when(request.getCookies()).thenReturn(cookies);

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.accessDeniedHandler, times(1)).handle(any(), any(), any());
    verifyNoMoreInteractions(this.filterChain);
  }

  @Test
  public void doFilterInternal_Should_callAccessDeniedHandler_When_cookiesAreNull()
      throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("uri");
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader(CSRF_HEADER)).thenReturn("csrfHeaderTokenValue");

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.accessDeniedHandler, times(1)).handle(any(), any(), any());
    verifyNoMoreInteractions(this.filterChain);
  }

  @Test
  public void
      doFilterInternal_Should_callAccessDeniedHandler_When_csrfHeaderIsNotEqualToCookieToken()
          throws IOException, ServletException {
    when(request.getRequestURI()).thenReturn("uri");
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader(CSRF_HEADER)).thenReturn("csrfHeaderTokenValue");
    Cookie[] cookies = {new Cookie(CSRF_COOKIE, "csrfCookieTokenValue")};
    when(request.getCookies()).thenReturn(cookies);

    this.csrfFilter.doFilterInternal(request, response, filterChain);

    verify(this.accessDeniedHandler, times(1)).handle(any(), any(), any());
    verifyNoMoreInteractions(this.filterChain);
  }
}
