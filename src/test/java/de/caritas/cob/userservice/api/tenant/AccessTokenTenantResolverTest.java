package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.google.common.collect.Maps;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class AccessTokenTenantResolverTest {
  @Mock HttpServletRequest authenticatedRequest;

  @InjectMocks AccessTokenTenantResolver accessTokenTenantResolver;

  @Test
  void resolve_Should_ResolveTenantId_When_TenantIdInAccessTokenClaim() {
    // given
    HashMap<String, Object> claimMap = givenClaimMapContainingTenantId(1);
    JwtAuthenticationToken token = givenJwtAuthenticationToken(claimMap);
    when(authenticatedRequest.getUserPrincipal()).thenReturn(token);

    // when
    Optional<Long> resolvedTenantId = accessTokenTenantResolver.resolve(authenticatedRequest);

    // then
    assertThat(resolvedTenantId).isEqualTo(Optional.of(1L));
  }

  @Test
  void resolve_Should_ReturnEmpty_When_PrincipalIsNull() {
    when(authenticatedRequest.getUserPrincipal()).thenReturn(null);

    Optional<Long> resolvedTenantId = accessTokenTenantResolver.resolve(authenticatedRequest);

    assertThat(resolvedTenantId).isEmpty();
  }

  @Test
  void resolve_Should_ReturnEmpty_When_PrincipalIsNotJwtAuthenticationToken() {
    Principal nonJwtPrincipal = () -> "non-jwt-user";
    when(authenticatedRequest.getUserPrincipal()).thenReturn(nonJwtPrincipal);

    Optional<Long> resolvedTenantId = accessTokenTenantResolver.resolve(authenticatedRequest);

    assertThat(resolvedTenantId).isEmpty();
  }

  @Test
  void canResolve_Should_ReturnFalse_When_PrincipalIsNull() {
    when(authenticatedRequest.getUserPrincipal()).thenReturn(null);

    assertThat(accessTokenTenantResolver.canResolve(authenticatedRequest)).isFalse();
  }

  @Test
  void canResolve_Should_ReturnFalse_When_PrincipalIsNotJwtAuthenticationToken() {
    Principal nonJwtPrincipal = () -> "non-jwt-user";
    when(authenticatedRequest.getUserPrincipal()).thenReturn(nonJwtPrincipal);

    assertThat(accessTokenTenantResolver.canResolve(authenticatedRequest)).isFalse();
  }

  private HashMap<String, Object> givenClaimMapContainingTenantId(Integer tenantId) {
    HashMap<String, Object> claimMap = Maps.newHashMap();
    claimMap.put("tenantId", tenantId);
    return claimMap;
  }

  private JwtAuthenticationToken givenJwtAuthenticationToken(HashMap<String, Object> claims) {
    Jwt jwt =
        Jwt.withTokenValue("token").header("alg", "none").claims(map -> map.putAll(claims)).build();
    return new JwtAuthenticationToken(jwt);
  }
}
