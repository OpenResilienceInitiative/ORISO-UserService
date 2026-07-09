package de.caritas.cob.userservice.api.adapters.keycloak.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeycloakConfigTest {

  private final KeycloakConfig keycloakConfig = new KeycloakConfig();

  @Test
  void resolveUsernameClaim_ShouldPreferMappedUsernameClaim_WhenPresent() {
    var usernameClaim =
        keycloakConfig.resolveUsernameClaim(Map.of("username", "mapped"), "preferred");

    assertThat(usernameClaim).isEqualTo("mapped");
  }

  @Test
  void resolveUsernameClaim_ShouldFallbackToPreferredUsername_WhenMappedClaimIsMissing() {
    var usernameClaim = keycloakConfig.resolveUsernameClaim(Map.of(), "preferred");

    assertThat(usernameClaim).isEqualTo("preferred");
  }

  @Test
  void resolveUsernameClaim_ShouldUseFirstTextValue_WhenMappedClaimIsCollection() {
    var usernameClaim =
        keycloakConfig.resolveUsernameClaim(Map.of("username", List.of("", "mapped")), "preferred");

    assertThat(usernameClaim).isEqualTo("mapped");
  }

  @Test
  void resolveUsernameClaim_ShouldFallbackToPreferredUsername_WhenMappedClaimIsBlank() {
    var usernameClaim = keycloakConfig.resolveUsernameClaim(Map.of("username", ""), "preferred");

    assertThat(usernameClaim).isEqualTo("preferred");
  }

  @Test
  void resolveTenantIdClaim_ShouldUseFirstTextValue_WhenMappedClaimIsCollection() {
    var tenantIdClaim = keycloakConfig.resolveTenantIdClaim(Map.of("tenantId", List.of("", "7")));

    assertThat(tenantIdClaim).isEqualTo("7");
  }
}
