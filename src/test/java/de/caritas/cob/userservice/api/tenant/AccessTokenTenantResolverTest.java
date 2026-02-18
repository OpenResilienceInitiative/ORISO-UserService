package de.caritas.cob.userservice.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class AccessTokenTenantResolverTest {
  @Mock HttpServletRequest authenticatedRequest;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  JwtAuthenticationToken token;

  @InjectMocks AccessTokenTenantResolver accessTokenTenantResolver;

  @Test
  void resolve_Should_ResolveTenantId_When_TenantIdInAccessTokenClaim() {
    // given
    when(authenticatedRequest.getUserPrincipal()).thenReturn(token);

    HashMap<String, Object> claimMap = givenClaimMapContainingTenantId(1);
    when(token.getToken().getClaims()).thenReturn(claimMap);

    // when
    Optional<Long> resolvedTenantId = accessTokenTenantResolver.resolve(authenticatedRequest);

    // then
    assertThat(resolvedTenantId).isEqualTo(Optional.of(1L));
  }

  private HashMap<String, Object> givenClaimMapContainingTenantId(Integer tenantId) {
    HashMap<String, Object> claimMap = Maps.newHashMap();
    claimMap.put("tenantId", tenantId);
    return claimMap;
  }
}
