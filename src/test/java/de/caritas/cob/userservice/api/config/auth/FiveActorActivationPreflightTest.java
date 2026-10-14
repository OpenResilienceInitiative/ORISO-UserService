package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The public startup boundary must not activate C over unmigrated native account flows. */
class FiveActorActivationPreflightTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              SafeLegacyClients.class,
              ServiceIdentitySeparationCheck.class,
              TaskAwareIdentityClientConfig.class)
          .withPropertyValues("multitenancy.enabled=true");

  @Test
  void groupedProductionActivationRefusesUntilAccountAndLifecycleGatesAreImplemented() {
    context
        .withPropertyValues("TASK_IDENTITY_MODEL=FIVE_ACTORS")
        .run(
            application -> {
              assertThat(application).hasFailed();
              assertThat(rootFailure(application.getStartupFailure()))
                  .hasMessageContaining("Five-actor production activation is gated");
            });
  }

  @Test
  void absentModeRetainsValidLegacyStartup() {
    context.run(application -> assertThat(application).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "TASKS", "five_actors"})
  void blankAndUnsupportedModesRefuseInsteadOfFallingThroughToLegacy(String mode) {
    context
        .withPropertyValues("TASK_IDENTITY_MODEL=" + mode)
        .run(
            application -> {
              assertThat(application).hasFailed();
              assertThat(rootFailure(application.getStartupFailure()))
                  .hasMessage("Unsupported task identity model");
            });
  }

  private static Throwable rootFailure(Throwable failure) {
    while (failure.getCause() != null) failure = failure.getCause();
    return failure;
  }

  @Configuration(proxyBeanMethods = false)
  static class SafeLegacyClients {
    @Bean
    KeycloakCustomConfig keycloakCustomConfig() {
      var configuration = new KeycloakCustomConfig();
      configuration.setAdminClientId("backend-admin");
      configuration.setAdminClientSecret("synthetic-distinct-admin-credential");
      configuration.setAdminServiceSubject("synthetic-admin-subject");
      configuration.setAppClientId("app");
      return configuration;
    }

    @Bean
    IdentityConfig identityClientConfig() {
      var technical = new TechnicalUserConfig();
      technical.setClientId("backend-technical");
      technical.setClientSecret("synthetic-distinct-technical-credential");
      var identity = new IdentityConfig();
      identity.setTechnicalUser(technical);
      return identity;
    }
  }
}
