package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class TechnicalUserTenantResolverTest {
  public static final long TECHNICAL_CONTEXT = 0L;
  @Mock HttpServletRequest authenticatedRequest;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  JwtAuthenticationToken token;

  @InjectMocks TechnicalOrSuperAdminUserTenantResolver technicalOrSuperadminUserTenantResolver;

  @Test
  void resolve_should_ResolveTechnicalTenantId_ForTechnicalUserRole() {
    // given
    when(authenticatedRequest.getUserPrincipal()).thenReturn(token);
    when(token.getToken()).thenReturn(buildJwtWithRealmRole("technical"));
    var resolved = technicalOrSuperadminUserTenantResolver.resolve(authenticatedRequest);
    // then
    assertThat(resolved).contains(TECHNICAL_CONTEXT);
  }

  @Test
  void resolve_should_NotResolveTenantId_When_NonTechnicalUserRole() {
    // given
    when(authenticatedRequest.getUserPrincipal()).thenReturn(token);
    when(token.getToken()).thenReturn(buildJwtWithRealmRole("another-role"));
    var resolved = technicalOrSuperadminUserTenantResolver.resolve(authenticatedRequest);
    // then
    assertThat(resolved).isEmpty();
  }

  private Jwt buildJwtWithRealmRole(String realmRole) {
    Map<String, Object> headers = new HashMap<>();
    headers.put("alg", "none");
    Map<String, Object> claims = new HashMap<>();
    Map<String, Object> realmAccess = new HashMap<>();
    realmAccess.put("roles", List.of(realmRole));
    claims.put("realm_access", realmAccess);
    return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), headers, claims);
  }
}
