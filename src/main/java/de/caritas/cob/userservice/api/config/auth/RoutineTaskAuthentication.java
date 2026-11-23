package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;

/** Selects one explicit operation identity. Grouped failures never use legacy/human credentials. */
public final class RoutineTaskAuthentication {
  private RoutineTaskAuthentication() {}

  public static String accessToken(
      TaskPurpose purpose, IdentityClientConfig config, IdentityAuthentication authentication) {
    String token;
    if (config.isFiveActorMode()) {
      token = config.taskAccessToken(purpose);
    } else {
      var legacy = config.getTechnicalUser();
      if (legacy == null || blank(legacy.getClientId()) || blank(legacy.getClientSecret())) {
        throw new IllegalStateException("Identity technical account is not configured");
      }
      var login = authentication.loginService(legacy.getClientId(), legacy.getClientSecret());
      token = login == null ? null : login.accessToken();
    }
    if (blank(token)) throw new IllegalStateException("Service authentication unavailable");
    return token;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
