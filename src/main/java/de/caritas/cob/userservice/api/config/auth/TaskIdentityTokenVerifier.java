package de.caritas.cob.userservice.api.config.auth;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/** Cryptographically verifies the grant before a credential is used for its bound task. */
@Component
@RequiredArgsConstructor
public class TaskIdentityTokenVerifier {
  private final JwtDecoder decoder;

  public org.springframework.security.oauth2.jwt.Jwt verify(
      TaskIdentityCredentials identity, String token) {
    try {
      var jwt = decoder.decode(token);
      var realm = jwt.getClaimAsMap("realm_access");
      Object actualRoles = realm == null ? null : realm.get("roles");
      var resources = jwt.getClaimAsMap("resource_access");
      if (identity.getTask() == null
          || !(actualRoles instanceof java.util.Collection<?> roles)
          || !new java.util.HashSet<>(roles).equals(identity.getTask().roles())
          || !new java.util.HashSet<>(
                  jwt.getAudience() == null ? java.util.List.of() : jwt.getAudience())
              .equals(identity.getTask().audiences())
          || (resources != null && !resources.isEmpty())
          || !identity.getServiceSubject().equals(jwt.getSubject())
          || !identity.getClientId().equals(jwt.getClaimAsString("azp"))
          || jwt.getExpiresAt() == null
          || !jwt.getExpiresAt().isAfter(Instant.now())) {
        throw new AccessDeniedException(
            "Task identity grant does not match its configured binding");
      }
      return jwt;
    } catch (JwtException | IllegalArgumentException exception) {
      throw new AccessDeniedException("Task identity grant is invalid");
    }
  }
}
