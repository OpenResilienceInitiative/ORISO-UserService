package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/** Five-actor runtime selection must fail closed until every actor binding is configured. */
class FiveActorActivationPreflightTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(
              SafeLegacyClients.class,
              ServiceIdentitySeparationCheck.class,
              TaskAwareIdentityClientConfig.class)
          .withPropertyValues("multitenancy.enabled=true");

  @Test
  void groupedProductionActivationRefusesWithoutAllActorBindings() {
    context
        .withPropertyValues("TASK_IDENTITY_MODEL=FIVE_ACTORS")
        .run(
            application -> {
              assertThat(application).hasFailed();
              assertThat(rootFailure(application.getStartupFailure()))
                  .hasMessage("Configure five distinct native task actor bindings");
            });
  }

  @Test
  void groupedProductionActivationStartsWithFiveDistinctActorBindings() {
    context
        .withPropertyValues(actorBindings())
        .run(application -> assertThat(application).hasNotFailed());
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

  private static String[] actorBindings() {
    return new String[] {
      "TASK_IDENTITY_MODEL=FIVE_ACTORS",
      "IDENTITY_SETUP_CLIENT_ID=backend-setup",
      "IDENTITY_SETUP_CLIENT_SECRET=synthetic-setup-secret",
      "IDENTITY_SETUP_SERVICE_SUBJECT=native-setup-subject",
      "IDENTITY_ACCOUNT_CREATE_CLIENT_ID=backend-account-create",
      "IDENTITY_ACCOUNT_CREATE_CLIENT_SECRET=synthetic-account-create-secret",
      "IDENTITY_ACCOUNT_CREATE_SERVICE_SUBJECT=native-account-create-subject",
      "IDENTITY_ACCOUNT_ADMIN_CLIENT_ID=backend-account-admin",
      "IDENTITY_ACCOUNT_ADMIN_CLIENT_SECRET=synthetic-account-admin-secret",
      "IDENTITY_ACCOUNT_ADMIN_SERVICE_SUBJECT=native-account-admin-subject",
      "IDENTITY_MAIL_CLIENT_ID=backend-mail",
      "IDENTITY_MAIL_CLIENT_SECRET=synthetic-mail-secret",
      "IDENTITY_MAIL_SERVICE_SUBJECT=native-mail-subject",
      "IDENTITY_RUNTIME_CLIENT_ID=backend-runtime",
      "IDENTITY_RUNTIME_CLIENT_SECRET=synthetic-runtime-secret",
      "IDENTITY_RUNTIME_SERVICE_SUBJECT=native-runtime-subject"
    };
  }

  // Explicit runner registration only; AppConfig also scans test component stereotypes.
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
