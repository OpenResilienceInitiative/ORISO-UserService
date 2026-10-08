package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.config.auth.TaskIdentity;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskIdentityTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Obtains only the explicitly selected task grant; never uses a human or legacy admin grant. */
@Component
@RequiredArgsConstructor
public class TaskIdentityGrant {
  private final TaskIdentityConfiguration identities;
  private final KeycloakAuthClient authentication;
  private final TaskIdentityTokenVerifier verifier;

  public record VerifiedGrant(String token, org.springframework.security.oauth2.jwt.Jwt claims) {
    @Override
    public String toString() {
      return "VerifiedGrant[redacted]";
    }
  }

  public String token(TaskIdentity task) {
    return verified(task).token();
  }

  public VerifiedGrant verified(TaskIdentity task) {
    var identity = identities.require(task);
    var response = authentication.loginService(identity.getClientId(), identity.getClientSecret());
    return new VerifiedGrant(
        response.getAccessToken(), verifier.verify(identity, response.getAccessToken()));
  }
}
