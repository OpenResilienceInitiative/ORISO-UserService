package de.caritas.cob.userservice.api.config.auth;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Pure receiving-token binding; endpoint owners supply their exact role policy. */
final class ExactTaskBinding {
  private ExactTaskBinding() {}

  static boolean permits(
      Authentication authentication,
      String client,
      String subject,
      String audience,
      Set<String> expectedRoles) {
    if (client == null
        || client.isBlank()
        || subject == null
        || subject.isBlank()
        || audience == null
        || audience.isBlank()
        || !(authentication instanceof JwtAuthenticationToken verified)
        || !verified.isAuthenticated()) return false;
    var jwt = verified.getToken();
    var realm = jwt.getClaimAsMap("realm_access");
    var resources = jwt.getClaimAsMap("resource_access");
    Object value = realm == null ? null : realm.get("roles");
    return subject.equals(jwt.getSubject())
        && client.equals(jwt.getClaimAsString("azp"))
        && jwt.getAudience().contains(audience)
        && value instanceof Collection<?> roles
        && new HashSet<>(roles).equals(expectedRoles)
        && (resources == null || resources.isEmpty());
  }
}
