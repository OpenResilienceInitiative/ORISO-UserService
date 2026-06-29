package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class TechnicalUserTenantResolverTest {
  public static final long TECHNICAL_CONTEXT = 0L;
  @Mock HttpServletRequest authenticatedRequest;

  @InjectMocks TechnicalOrSuperAdminUserTenantResolver technicalOrSuperadminUserTenantResolver;

  @Test
  void resolve_should_ResolveTechnicalTenantId_ForTechnicalUserRole() {
    // given
    when(authenticatedRequest.getUserPrincipal())
        .thenReturn(givenJwtAuthenticationToken("technical"));
    var resolved = technicalOrSuperadminUserTenantResolver.resolve(authenticatedRequest);
    // then
    assertThat(resolved).contains(TECHNICAL_CONTEXT);
  }

  @Test
  void resolve_should_NotResolveTenantId_When_NonTechnicalUserRole() {
    // given
    when(authenticatedRequest.getUserPrincipal())
        .thenReturn(givenJwtAuthenticationToken("another-role"));
    var resolved = technicalOrSuperadminUserTenantResolver.resolve(authenticatedRequest);
    // then
    assertThat(resolved).isEmpty();
  }

  private JwtAuthenticationToken givenJwtAuthenticationToken(String role) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
    return new JwtAuthenticationToken(jwt);
  }
}
