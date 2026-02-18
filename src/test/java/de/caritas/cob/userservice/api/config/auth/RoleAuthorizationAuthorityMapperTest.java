package de.caritas.cob.userservice.api.config.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
public class RoleAuthorizationAuthorityMapperTest {

  private final Set<String> roles =
      Stream.of(UserRole.values()).map(UserRole::getValue).collect(Collectors.toSet());

  @Test
  public void roleAuthorizationAuthorityMapper_Should_GrantCorrectAuthorities() {
    RoleAuthorizationAuthorityMapper roleAuthorizationAuthorityMapper =
        new RoleAuthorizationAuthorityMapper();
    Set<GrantedAuthority> mappedAuthorities =
        roleAuthorizationAuthorityMapper.mapAuthorities(
            roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet()))
            .stream()
            .map(GrantedAuthority.class::cast)
            .collect(Collectors.toSet());

    Set<SimpleGrantedAuthority> expectedGrantendAuthorities = new HashSet<>();
    roles.forEach(
        roleName -> {
          expectedGrantendAuthorities.addAll(
              Authority.getAuthoritiesByUserRole(UserRole.getRoleByValue(roleName).get()).stream()
                  .map(SimpleGrantedAuthority::new)
                  .collect(Collectors.toSet()));
        });

    assertThat(expectedGrantendAuthorities, containsInAnyOrder(mappedAuthorities.toArray()));
  }
}
