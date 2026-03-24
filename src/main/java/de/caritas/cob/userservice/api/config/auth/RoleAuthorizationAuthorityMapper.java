package de.caritas.cob.userservice.api.config.auth;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.stereotype.Component;

/** Own implementation of the Spring GrantedAuthoritiesMapper. */
@Component
public class RoleAuthorizationAuthorityMapper implements GrantedAuthoritiesMapper {

  @Override
  public Collection<? extends GrantedAuthority> mapAuthorities(
      Collection<? extends GrantedAuthority> authorities) {
    Set<String> roleNames =
        authorities.stream()
            .map(GrantedAuthority::getAuthority)
            .map(this::normalizeRoleName)
            .collect(Collectors.toSet());

    return mapAuthorities(roleNames);
  }

  /**
   * Keycloak/Spring can provide authorities with a ROLE_ prefix (e.g. ROLE_tenant-admin). Strip
   * that prefix so role lookup works against our canonical realm role values.
   */
  private String normalizeRoleName(String authority) {
    if (authority == null) {
      return null;
    }
    String normalized = authority.toLowerCase();
    if (normalized.startsWith("role_")) {
      normalized = normalized.substring("role_".length());
    }
    // Some adapters normalize role names to underscore notation (e.g. USER_ADMIN).
    normalized = normalized.replace('_', '-');
    return normalized;
  }

  private Set<GrantedAuthority> mapAuthorities(Set<String> roleNames) {
    return roleNames.parallelStream()
        .map(UserRole::getRoleByValue)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(Authority::getAuthoritiesByUserRole)
        .flatMap(Collection::parallelStream)
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toSet());
  }
}
