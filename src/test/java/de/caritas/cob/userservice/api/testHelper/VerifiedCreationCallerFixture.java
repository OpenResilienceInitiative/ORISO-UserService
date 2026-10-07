package de.caritas.cob.userservice.api.testHelper;

import java.time.Instant;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Synthetic verified human at the explicitly authorized creation seam; no transport credentials.
 */
public final class VerifiedCreationCallerFixture {
  private VerifiedCreationCallerFixture() {}

  public static void install() {
    var jwt =
        Jwt.withTokenValue("verified-test-human")
            .header("alg", "RS256")
            .subject("human-admin")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("azp", "admin")
            .claim("realm_access", java.util.Map.of("roles", List.of("user-admin", "tenant-admin")))
            .build();
    var authorities =
        List.of(
                "AUTHORIZATION_USER_ADMIN",
                "AUTHORIZATION_TENANT_ADMIN",
                "AUTHORIZATION_CONSULTANT_CREATE")
            .stream()
            .map(SimpleGrantedAuthority::new)
            .toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
  }
}
