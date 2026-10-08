package de.caritas.cob.userservice.api.actions.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

class DeactivateAuthorizedIdentityActionCommandTest {
  @Test
  void downstreamFailureMustPropagateBeforeSessionDeletionCanProceed() {
    var port = mock(IdentityDeactivator.class);
    var user =
        User.builder()
            .userId("owned-user")
            .tenantId(42L)
            .username("owned")
            .email("owned@example.com")
            .build();
    var session =
        Session.builder()
            .id(17L)
            .user(user)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("12345")
            .status(Session.SessionStatus.IN_PROGRESS)
            .build();
    user.setSessions(Set.of(session));
    var caller =
        new JwtAuthenticationToken(
            Jwt.withTokenValue("verified-fixture")
                .header("alg", "RS256")
                .subject("consultant")
                .build(),
            List.of(new SimpleGrantedAuthority("AUTHORIZATION_CONSULTANT_DEFAULT")));
    var origin = IdentityCommandAuthorization.lastSessionDeletion(session, caller);
    var failure = new ResourceAccessException("native provider unavailable");
    doThrow(failure).when(port).deactivateUser("owned-user", origin);
    assertThatThrownBy(
            () ->
                new DeactivateAuthorizedIdentityActionCommand(port)
                    .execute(new IdentityDeactivationTarget(user, origin)))
        .isInstanceOf(ResponseStatusException.class)
        .hasCause(failure)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(502));
  }
}
