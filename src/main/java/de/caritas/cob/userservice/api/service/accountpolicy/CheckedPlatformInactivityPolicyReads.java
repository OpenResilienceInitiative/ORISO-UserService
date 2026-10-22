package de.caritas.cob.userservice.api.service.accountpolicy;

import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
import java.time.Clock;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestTemplate;

/** The finite prelogin platform-default projection; ordinary tenant reads stay unchanged. */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "TASK_IDENTITY_MODEL",
    havingValue = "FIVE_ACTORS")
public final class CheckedPlatformInactivityPolicyReads {
  public CheckedPlatformInactivityPolicyReads(
      @org.springframework.beans.factory.annotation.Qualifier("restTemplate") RestTemplate http,
      TaskActorTokenBroker broker,
      JwtDecoder decoder,
      Environment environment,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${tenant.service.api.url}")
          String base) {}

  public Policy capture() {
    return null;
  }
}
