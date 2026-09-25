package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import org.junit.jupiter.api.Test;

class ServiceIdentitySeparationCheckTest {

  private final KeycloakCustomConfig keycloakConfig = new KeycloakCustomConfig();
  private final IdentityClientConfig identityConfig = mock(IdentityClientConfig.class);
  private final ServiceIdentitySeparationCheck check =
      new ServiceIdentitySeparationCheck(keycloakConfig, identityConfig);

  private void configure(String adminUsername, String technicalUsername) {
    keycloakConfig.setAdminUsername(adminUsername);
    var technical = new TechnicalUserConfig();
    technical.setUsername(technicalUsername);
    technical.setPassword("synthetic-password");
    when(identityConfig.getTechnicalUser()).thenReturn(technical);
  }

  @Test
  void warnsAndNamesBothPropertiesWhenOneIdentityDoesBothJobs() {
    configure("technical", " Technical ");

    try (var logs = LogbackCaptor.forClass(ServiceIdentitySeparationCheck.class)) {
      check.warnIfKeycloakAdminIsTheTechnicalUser();

      assertThat(logs.messages(Level.WARN))
          .singleElement()
          .satisfies(
              message ->
                  assertThat(message)
                      .contains(
                          "keycloak.config.admin-username",
                          "identity.technical-user.username",
                          "KEYCLOAK_CONFIG_ADMIN_USERNAME",
                          "IDENTITY_TECHNICAL_USER_USERNAME")
                      .doesNotContain("synthetic-password"));
    }
  }

  @Test
  void staysQuietWhenTheIdentitiesAreSeparate() {
    configure("userservice-keycloak-admin", "technical");

    try (var logs = LogbackCaptor.forClass(ServiceIdentitySeparationCheck.class)) {
      check.warnIfKeycloakAdminIsTheTechnicalUser();

      assertThat(logs.messages(Level.WARN)).isEmpty();
    }
  }

  @Test
  void staysQuietWhenATechnicalUserIsNotConfigured() {
    keycloakConfig.setAdminUsername("technical");
    when(identityConfig.getTechnicalUser()).thenReturn(null);

    try (var logs = LogbackCaptor.forClass(ServiceIdentitySeparationCheck.class)) {
      check.warnIfKeycloakAdminIsTheTechnicalUser();

      assertThat(logs.messages(Level.WARN)).isEmpty();
    }
  }
}
