package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.Environment;

class ServiceIdentitySeparationCheckTest {
  private final KeycloakCustomConfig admin = new KeycloakCustomConfig();
  private final IdentityClientConfig identity = mock(IdentityClientConfig.class);
  private final Environment environment = mock(Environment.class);
  private final TechnicalUserConfig technical = new TechnicalUserConfig();
  private final ServiceIdentitySeparationCheck check =
      new ServiceIdentitySeparationCheck(admin, identity, environment);

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
    org.junit.jupiter.api.Assertions.assertNull(check.scopedAccountCommands());
    check.verifyBackendClients();
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, check.scopedAccountCommands());
  }

  @Test
  void failedRevalidationRevokesTheLegacyCapabilityWithoutAuthenticationSideEffects() {
    configure();
    check.verifyBackendClients();
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, check.scopedAccountCommands());
    technical.setClientSecret("");
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, check::verifyBackendClients);
    org.junit.jupiter.api.Assertions.assertNull(check.scopedAccountCommands());
    org.mockito.Mockito.verify(identity, org.mockito.Mockito.never())
        .taskAccessToken(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void configuredRoutineFiveActorsRemainAcceptedWhileLifecycleIsDisabled() {
    configure();
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "taskIdentityModel", "FIVE_ACTORS");
    when(environment.getProperty("TASK_IDENTITY_MODEL", "LEGACY")).thenReturn("FIVE_ACTORS");
    for (var actor : TaskActor.values()) {
      String prefix = "IDENTITY_" + actor.name();
      when(environment.getProperty(prefix + "_CLIENT_ID")).thenReturn("fixture-" + actor.name());
      when(environment.getProperty(prefix + "_CLIENT_SECRET"))
          .thenReturn("synthetic-secret-" + actor.name());
      when(environment.getProperty(prefix + "_SERVICE_SUBJECT"))
          .thenReturn("fixture-subject-" + actor.name());
    }
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(check::verifyBackendClients);
    org.junit.jupiter.api.Assertions.assertNull(check.scopedAccountCommands());
  }

  @Test
  void unfinishedCoordinatedLifecycleRetainsItsActivationFence() {
    configure();
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "taskIdentityModel", "FIVE_ACTORS");
    when(environment.getProperty("ORISO_ACCOUNT_LIFECYCLE_ENABLED")).thenReturn("true");
    assertThatThrownBy(check::verifyBackendClients)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Five-actor production activation is gated until native account, lifecycle and coordinated receiver contracts are implemented");
    org.junit.jupiter.api.Assertions.assertNull(check.scopedAccountCommands());
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
