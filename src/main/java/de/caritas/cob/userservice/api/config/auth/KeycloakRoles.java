package de.caritas.cob.userservice.api.config.auth;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Keycloak roles of a token, read the one way authorization reads them: realm and client roles,
 * normalized as {@link RoleAuthorizationAuthorityMapper} does. Tenant resolution uses the same
 * answer, so a caller who holds an authority also passes the tenant checks built on it.
 */
public final class KeycloakRoles {

  private KeycloakRoles() {}

  /** Realm and client roles in canonical form, e.g. {@code ROLE_TENANT_ADMIN} → tenant-admin. */
  public static Set<String> of(Map<String, Object> claims) {
    var roles = new HashSet<String>();
    if (claims.get("realm_access") instanceof Map<?, ?> realmAccess) {
      addRoles(roles, realmAccess.get("roles"));
    }
    if (claims.get("resource_access") instanceof Map<?, ?> resourceAccess) {
      resourceAccess.values().stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .forEach(clientAccess -> addRoles(roles, clientAccess.get("roles")));
    }
    return roles;
  }

  /** Strips a {@code ROLE_} prefix and maps underscore notation to the realm role values. */
  static String normalize(String role) {
    // Role checks must not follow the container locale (Turkish lower-cases I to ı).
    String normalized = role.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("role_")) {
      normalized = normalized.substring("role_".length());
    }
    return normalized.replace('_', '-');
  }

  private static void addRoles(Set<String> roles, Object rolesClaim) {
    if (rolesClaim instanceof Collection<?> roleCollection) {
      roleCollection.stream()
          .filter(Objects::nonNull)
          .map(Object::toString)
          .map(KeycloakRoles::normalize)
          .forEach(roles::add);
    }
  }
}
