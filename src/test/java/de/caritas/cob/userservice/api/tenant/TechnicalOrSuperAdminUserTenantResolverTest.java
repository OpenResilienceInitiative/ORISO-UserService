package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class TechnicalOrSuperAdminUserTenantResolverTest {

  @Mock HttpServletRequest request;

  @InjectMocks TechnicalOrSuperAdminUserTenantResolver resolver;

  @Test
  void resolve_Should_ReturnZero_When_PrincipalHasTechnicalRole() {
    // given
    JwtAuthenticationToken token = givenJwtWithRoles("technical");
    when(request.getUserPrincipal()).thenReturn(token);

    // when
    Optional<Long> result = resolver.resolve(request);

    // then
    assertThat(result).isEqualTo(Optional.of(0L));
  }

  @Test
  void resolve_Should_ReturnEmpty_When_PrincipalLacksTechnicalRole() {
    // given
    JwtAuthenticationToken token = givenJwtWithRoles("user");
    when(request.getUserPrincipal()).thenReturn(token);

    // when
    Optional<Long> result = resolver.resolve(request);

    // then
    assertThat(result).isEmpty();
  }

  @Test
  void resolve_Should_ReturnEmpty_When_PrincipalIsNull() {
    when(request.getUserPrincipal()).thenReturn(null);

    assertThat(resolver.resolve(request)).isEmpty();
  }

  @Test
  void resolve_Should_ReturnEmpty_When_PrincipalIsNotJwtAuthenticationToken() {
    Principal nonJwtPrincipal = () -> "non-jwt-user";
    when(request.getUserPrincipal()).thenReturn(nonJwtPrincipal);

    // should not throw ClassCastException
    assertThat(resolver.resolve(request)).isEmpty();
  }

  @Test
  void canResolve_Should_ReturnTrue_When_PrincipalHasTechnicalRole() {
    JwtAuthenticationToken token = givenJwtWithRoles("technical");
    when(request.getUserPrincipal()).thenReturn(token);

    assertThat(resolver.canResolve(request)).isTrue();
  }

  @Test
  void canResolve_Should_ReturnFalse_When_PrincipalIsNull() {
    when(request.getUserPrincipal()).thenReturn(null);

    assertThat(resolver.canResolve(request)).isFalse();
  }

  @Test
  void canResolve_Should_ReturnFalse_When_PrincipalIsNotJwtAuthenticationToken() {
    Principal nonJwtPrincipal = () -> "non-jwt-user";
    when(request.getUserPrincipal()).thenReturn(nonJwtPrincipal);

    assertThat(resolver.canResolve(request)).isFalse();
  }

  private JwtAuthenticationToken givenJwtWithRoles(String... roles) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .build();
    return new JwtAuthenticationToken(jwt);
  }
}

