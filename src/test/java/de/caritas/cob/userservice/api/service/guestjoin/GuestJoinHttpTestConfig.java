package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.adapters.web.controller.GuestJoinController;
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
  GuestJoinController.class,
  ApiResponseEntityExceptionHandler.class
})
class GuestJoinHttpTestConfig {
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
