package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeycloakRolesTest {

  @Test
  void of_Should_ReadRealmAndClientRoles_InCanonicalForm() {
    var roles =
        KeycloakRoles.of(
            Map.of(
                "realm_access", Map.of("roles", List.of("ROLE_AGENCY_ADMIN")),
                "resource_access", Map.of("app", Map.of("roles", List.of("tenant-admin")))));

    assertThat(roles).containsExactlyInAnyOrder("agency-admin", "tenant-admin");
  }

  @Test
  void of_Should_NotDependOnTheJvmLocale() {
    // Turkish lower-cases I to a dotless ı, which would no longer match "tenant-admin".
    var previous = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      var roles =
          KeycloakRoles.of(Map.of("realm_access", Map.of("roles", List.of("ROLE_TENANT_ADMIN"))));

      assertThat(roles).containsExactly("tenant-admin");
    } finally {
      Locale.setDefault(previous);
    }
  }
}
