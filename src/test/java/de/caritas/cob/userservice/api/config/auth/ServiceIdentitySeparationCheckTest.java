package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceIdentitySeparationCheckTest {
  private final KeycloakCustomConfig admin = new KeycloakCustomConfig();
  private final IdentityClientConfig identity = mock(IdentityClientConfig.class);
  private final TechnicalUserConfig technical = new TechnicalUserConfig();
  private final ServiceIdentitySeparationCheck check =
      new ServiceIdentitySeparationCheck(admin, identity);

  private void configure() {
    admin.setAdminClientId("backend-admin");
    admin.setAppClientId("app");
    admin.setAdminClientSecret("synthetic-admin-secret");
    admin.setAdminServiceSubject("admin-service-subject");
    technical.setClientId("backend-technical");
    technical.setClientSecret("synthetic-technical-secret");
    when(identity.getTechnicalUser()).thenReturn(technical);
  }

  @Test
  void distinctClientsAndSecretsAreAccepted() {
    configure();
    check.verifyBackendClients();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "same-clients",
        "admin-app",
        "technical-app",
        "same-secrets",
        "missing-secret",
        "missing-admin-subject"
      })
  void unsafeClientConfigurationAbortsStartupWithoutExposingSecrets(String reason) {
    configure();
    switch (reason) {
      case "same-clients" -> technical.setClientId("backend-admin");
      case "admin-app" -> admin.setAdminClientId("app");
      case "technical-app" -> technical.setClientId("app");
      case "same-secrets" -> technical.setClientSecret("synthetic-admin-secret");
      case "missing-secret" -> technical.setClientSecret("");
      case "missing-admin-subject" -> admin.setAdminServiceSubject(" ");
    }
    assertThatThrownBy(check::verifyBackendClients)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("synthetic-admin-secret")
        .hasMessageNotContaining("synthetic-technical-secret")
        .hasNoCause();
  }
}
