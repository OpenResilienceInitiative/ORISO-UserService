package de.caritas.cob.userservice.api.testHelper;

/** Maps the actual dispatched synthetic request through the production JWT mapper. */
@org.springframework.boot.test.context.TestConfiguration
@org.springframework.context.annotation.Profile("verified-request-caller")
public class VerifiedRequestCallerFixture {
  // The stock factory's injected servlet request loses the caller in this synthetic-JWT
  // context. Keep production JWT mapping and permissions; supply the dispatched request.
  @org.springframework.context.annotation.Bean
  @org.springframework.context.annotation.Primary
  @org.springframework.context.annotation.Scope(
      value = "request",
      proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  de.caritas.cob.userservice.api.helper.AuthenticatedUser mappedCaller() {
    var request =
        ((org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder
                    .currentRequestAttributes())
            .getRequest();
    var config = new de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig();
    config.setPrincipalAttribute("preferred_username");
    return config.authenticatedUser(
        request, new de.caritas.cob.userservice.api.helper.UsernameTranscoder());
  }
}
