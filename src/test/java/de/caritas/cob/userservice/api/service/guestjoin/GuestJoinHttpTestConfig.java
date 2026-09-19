package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.adapters.web.controller.GuestJoinControllerDelegate;
import de.caritas.cob.userservice.api.adapters.web.controller.interceptor.ApiResponseEntityExceptionHandler;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.RoleAuthorizationAuthorityMapper;
import de.caritas.cob.userservice.api.config.auth.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real HTTP/security wiring; the parent test supplies its real transactional Join service. */
@TestConfiguration
@EnableWebMvc
@Import({
  SecurityConfig.class,
  RoleAuthorizationAuthorityMapper.class,
  GuestJoinControllerDelegate.class,
  ApiResponseEntityExceptionHandler.class,
  GuestJoinHttpTestConfig.JoinEndpoint.class
})
class GuestJoinHttpTestConfig {

  /**
   * Exercises the delegate over real HTTP. It maps the path the plain way, without the generated
   * contract's consumes/produces conditions, so it stays harmless if another integration context
   * ever scans it: the generated mapping is the more specific one and wins. That the production
   * controller really overrides the generated operation is guarded by
   * GeneratedContractOverrideTest.
   */
  @org.springframework.web.bind.annotation.RestController
  @lombok.RequiredArgsConstructor
  static class JoinEndpoint {
    private final GuestJoinControllerDelegate delegate;

    @org.springframework.web.bind.annotation.PostMapping("/users/invitelinks/{token}/join")
    public org.springframework.http.ResponseEntity<
            de.caritas.cob.userservice.api.adapters.web.dto.GuestJoinResponse>
        join(
            @org.springframework.web.bind.annotation.PathVariable String token,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
                de.caritas.cob.userservice.api.adapters.web.dto.GuestJoinRequest request) {
      return delegate.joinGuestInvitation(token, request);
    }
  }

  @Bean
  JwtDecoder jwtDecoder() {
    return org.mockito.Mockito.mock(JwtDecoder.class);
  }

  @Bean
  CsrfSecurityProperties csrfSecurityProperties() {
    var properties = new CsrfSecurityProperties();
    var cookie = new CsrfSecurityProperties.ConfigProperty();
    cookie.setProperty("CSRF-TOKEN");
    properties.setCookie(cookie);
    var header = new CsrfSecurityProperties.ConfigProperty();
    header.setProperty("X-CSRF-Token");
    properties.setHeader(header);
    var whitelist = new CsrfSecurityProperties.Whitelist();
    var allowed = new CsrfSecurityProperties.ConfigProperty();
    allowed.setProperty("X-CSRF-Whitelist");
    whitelist.setHeader(allowed);
    properties.setWhitelist(whitelist);
    return properties;
  }
}
