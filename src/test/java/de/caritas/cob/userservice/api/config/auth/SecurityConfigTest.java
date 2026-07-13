package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.config.annotation.web.builders.WebSecurity;

class SecurityConfigTest {

  @Test
  void publicAskerRegistrationShouldPassThroughSecurityFilterChain() {
    var securityConfig = new SecurityConfig(mock(CsrfSecurityProperties.class), null, null);
    var webSecurity = mock(WebSecurity.class);
    var ignoredRequests = mock(WebSecurity.IgnoredRequestConfigurer.class);
    when(webSecurity.ignoring()).thenReturn(ignoredRequests);

    securityConfig.webSecurityCustomizer().customize(webSecurity);

    var ignoredPaths = ArgumentCaptor.forClass(String[].class);
    verify(ignoredRequests).requestMatchers(ignoredPaths.capture());
    assertThat(ignoredPaths.getValue())
        .containsExactly("/actuator/**")
        .doesNotContain("/users/askers/new");
  }
}
