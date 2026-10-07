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

  @Test
  void onlyVerifiedExactIncomingNotificationOrImportBindingsResolveGlobalTaskContext() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        resolver, "notificationTaskClient", "backend-notification-dispatch");
    org.springframework.test.util.ReflectionTestUtils.setField(
        resolver, "notificationTaskSubject", "dispatch-subject");
    org.springframework.test.util.ReflectionTestUtils.setField(
        resolver, "importTaskClient", "backend-consultant-import");
    org.springframework.test.util.ReflectionTestUtils.setField(
        resolver, "importTaskSubject", "import-subject");
    org.springframework.test.util.ReflectionTestUtils.setField(
        resolver, "taskIdentityAudience", "userservice");
    for (var task :
        List.of(
            taskJwt(
                "dispatch-subject",
                "backend-notification-dispatch",
                "userservice",
                List.of("notification-dispatch", "notifications-technical")),
            taskJwt(
                "import-subject",
                "backend-consultant-import",
                "userservice",
                List.of("consultant-import")))) {
      when(request.getUserPrincipal()).thenReturn(task);
      assertThat(resolver.resolve(request)).isEqualTo(Optional.of(0L));
    }
    for (var denied :
        List.of(
            taskJwt("human", "app", "userservice", List.of("consultant-import")),
            taskJwt(
                "foreign",
                "backend-notification-dispatch",
                "userservice",
                List.of("notification-dispatch", "notifications-technical")),
            taskJwt(
                "import-subject",
                "backend-consultant-import",
                "agencyservice",
                List.of("consultant-import")),
            taskJwt(
                "import-subject",
                "backend-consultant-import",
                "userservice",
                List.of("consultant-import", "user-admin")),
            taskJwt(
                "config-subject",
                "backend-config-wizard",
                "userservice",
                List.of("config-wizard")))) {
      when(request.getUserPrincipal()).thenReturn(denied);
      assertThat(resolver.resolve(request)).isEmpty();
    }
  }

  private JwtAuthenticationToken taskJwt(
      String subject, String client, String audience, List<String> roles) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("verified-fixture")
            .header("alg", "RS256")
            .subject(subject)
            .claim("azp", client)
            .audience(List.of(audience))
            .claim("realm_access", Map.of("roles", roles))
            .build(),
        List.of());
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
