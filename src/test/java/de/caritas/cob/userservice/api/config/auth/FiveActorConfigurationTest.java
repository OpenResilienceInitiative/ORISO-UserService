package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class FiveActorConfigurationTest {
  @Test
  void explicitSetupBindingServesItsOperationAliasesWithoutAdditionalCredentials() {
    var configuration = configured(environment());
    var wizard = configuration.require(TaskPurpose.CONFIG_WIZARD);
    var reservations = configuration.require(TaskPurpose.INVITE_RESERVATIONS);
    assertThat(wizard.clientId()).isEqualTo("backend-setup");
    assertThat(wizard.serviceSubject()).isEqualTo("native-setup-subject");
    assertThat(reservations).isEqualTo(wizard);
    assertThat(wizard.toString()).doesNotContain("synthetic-setup-secret");
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLIENT_ID", "CLIENT_SECRET", "SERVICE_SUBJECT"})
  void actorToActorCredentialCollisionsRefuseEvenWhenRequestingADifferentActor(String field) {
    var environment = environment();
    environment.setProperty(
        "IDENTITY_ACCOUNT_ADMIN_" + field, environment.getProperty("IDENTITY_MAIL_" + field));
    assertThatThrownBy(() -> configured(environment).require(TaskPurpose.CONFIG_WIZARD))
        .hasMessage("Configure five distinct native task actor bindings")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLIENT_ID", "CLIENT_SECRET", "SERVICE_SUBJECT"})
  void incompleteAccountBindingRefusesBeforeAnyOtherActorCanIssue(String field) {
    var environment = environment();
    environment.setProperty("IDENTITY_ACCOUNT_CREATE_" + field, " ");
    assertThatThrownBy(() -> configured(environment).require(TaskPurpose.SMTP_SYNC))
        .hasMessage("Configure five distinct native task actor bindings")
        .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "admin-client",
        "human-client",
        "technical-client",
        "admin-secret",
        "technical-secret",
        "admin-subject"
      })
  void broadLegacyOrHumanCredentialsCannotBeReusedForAnActor(String collision) {
    var environment = environment();
    var admin = new KeycloakCustomConfig();
    admin.setAppClientId("app");
    admin.setAdminClientId("backend-admin");
    admin.setAdminClientSecret("synthetic-admin-secret");
    admin.setAdminServiceSubject("native-admin-subject");
    var technical = new TechnicalUserConfig();
    technical.setClientId("backend-technical");
    technical.setClientSecret("synthetic-technical-secret");
    var identity = new IdentityConfig();
    identity.setTechnicalUser(technical);
    switch (collision) {
      case "admin-client" -> environment.setProperty("IDENTITY_SETUP_CLIENT_ID", "backend-admin");
      case "human-client" -> environment.setProperty("IDENTITY_SETUP_CLIENT_ID", "app");
      case "technical-client" ->
          environment.setProperty("IDENTITY_SETUP_CLIENT_ID", "backend-technical");
      case "admin-secret" ->
          environment.setProperty("IDENTITY_SETUP_CLIENT_SECRET", "synthetic-admin-secret");
      case "technical-secret" ->
          environment.setProperty("IDENTITY_SETUP_CLIENT_SECRET", "synthetic-technical-secret");
      case "admin-subject" ->
          environment.setProperty("IDENTITY_SETUP_SERVICE_SUBJECT", "native-admin-subject");
    }
    var configuration = new TaskActorConfiguration(environment, admin, identity);
    assertThatThrownBy(() -> configuration.require(TaskPurpose.RUNTIME_POLICY))
        .hasMessage("Configure five distinct native task actor bindings")
        .hasNoCause();
  }

  @Test
  void defaultModeDoesNotRequireActorCredentialsOrAllowActorIssuance() {
    var configuration = configured(new MockEnvironment());
    assertThat(configuration.isEnabled()).isFalse();
    assertThatThrownBy(() -> configuration.require(TaskPurpose.SMTP_SYNC))
        .isInstanceOf(IllegalStateException.class);
  }

  static MockEnvironment environment() {
    var environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
    binding(environment, "SETUP", "setup");
    binding(environment, "ACCOUNT_CREATE", "account-create");
    binding(environment, "ACCOUNT_ADMIN", "account-admin");
    binding(environment, "MAIL", "mail");
    binding(environment, "RUNTIME", "runtime");
    return environment;
  }

  private static void binding(MockEnvironment environment, String actor, String suffix) {
    environment
        .withProperty("IDENTITY_" + actor + "_CLIENT_ID", "backend-" + suffix)
        .withProperty("IDENTITY_" + actor + "_CLIENT_SECRET", "synthetic-" + suffix + "-secret")
        .withProperty("IDENTITY_" + actor + "_SERVICE_SUBJECT", "native-" + suffix + "-subject");
  }

  static TaskActorConfiguration configured(MockEnvironment environment) {
    return new TaskActorConfiguration(
        environment, new KeycloakCustomConfig(), new IdentityConfig());
  }
}
