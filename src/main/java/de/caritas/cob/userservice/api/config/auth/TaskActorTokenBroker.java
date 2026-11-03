package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/** Staged confidential grants; full production activation has a separate preflight gate. */
@Component
public class TaskActorTokenBroker {
  private final TaskActorConfiguration configuration;
  private final KeycloakAuthClient authentication;
  private final JwtDecoder decoder;
  private final KeycloakConfig keycloak;

  public TaskActorTokenBroker(
      TaskActorConfiguration configuration,
      KeycloakAuthClient authentication,
      JwtDecoder decoder,
      KeycloakConfig keycloak) {
    this.configuration = configuration;
    this.authentication = authentication;
    this.decoder = decoder;
    this.keycloak = keycloak;
  }

  public TaskToken issue(TaskPurpose purpose) {
    var binding = configuration.require(purpose);
    if (binding.actor().audiences().isEmpty()) {
      throw new IllegalStateException("Native account actor issuance remains gated");
    }
    try {
      var response = authentication.loginService(binding.clientId(), binding.clientSecret());
      Jwt token = decoder.decode(response.getAccessToken());
      String issuer =
          keycloak.getAuthServerUrl().replaceAll("/+$", "") + "/realms/" + keycloak.getRealm();
      Object clientRoles = token.getClaims().get("resource_access");
      if (token.getIssuer() == null
          || !issuer.equals(token.getIssuer().toString())
          || !binding.clientId().equals(token.getClaimAsString("azp"))
          || !binding.serviceSubject().equals(token.getSubject())
          || token.getExpiresAt() == null
          || !token.getExpiresAt().isAfter(Instant.now())
          || !binding.actor().audiences().equals(new HashSet<>(token.getAudience()))
          || !binding.actor().roles().equals(realmRoles(token))
          || (clientRoles != null
              && (!(clientRoles instanceof Map<?, ?> roles) || !roles.isEmpty()))) {
        throw new IllegalStateException();
      }
      return new TaskToken(binding.actor(), response.getAccessToken(), response.getExpiresIn());
    } catch (RuntimeException failure) {
      // Neither tokens, credentials nor provider/decoder diagnostic bodies cross this boundary.
      throw new IllegalStateException("Task actor authentication unavailable");
    }
  }

  private static Set<String> realmRoles(Jwt token) {
    if (!(token.getClaims().get("realm_access") instanceof Map<?, ?> access)
        || !(access.get("roles") instanceof Collection<?> roles)
        || roles.stream().anyMatch(role -> !(role instanceof String))) {
      return Set.of();
    }
    var result = new HashSet<String>();
    for (Object role : roles) result.add((String) role);
    return result;
  }

  public record TaskToken(TaskActor actor, String accessToken, int expiresIn) {
    @Override
    public String toString() {
      return "TaskToken[actor=" + actor + ", token=redacted]";
    }
  }
}
