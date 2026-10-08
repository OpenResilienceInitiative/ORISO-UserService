package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskIdentityConfigurationTest {
  @Test
  void packagedTestingProfileCannotSupplyTaskSecretsSubjectsOrProofKeys() throws Exception {
    var environment = new org.springframework.mock.env.MockEnvironment();
    environment
        .getPropertySources()
        .addLast(
            new org.springframework.core.io.support.ResourcePropertySource(
                "packaged-testing", "file:src/main/resources/application-testing.properties"));
    var settings =
        org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind(
                "identity",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    TaskIdentityConfiguration.class))
            .get();
    assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class);
    for (var task : TaskIdentity.values()) {
      assertThat(environment.getProperty("identity.tasks." + task.key() + ".client-secret"))
          .isEmpty();
      assertThat(environment.getProperty("identity.tasks." + task.key() + ".service-subject"))
          .isEmpty();
    }
    for (var key :
        java.util.List.of(
            "provisioning-origin-key", "maintenance-origin-key", "wizard-policy-context-key"))
      assertThat(environment.getProperty("oriso.commands." + key)).isEmpty();
  }

  @Test
  void actualUserServiceOutboundRegistryDoesNotRequireConsultingTypeSmtpSecret() {
    assertThat(TaskIdentity.values())
        .extracting(TaskIdentity::key)
        .containsExactlyInAnyOrder(
            "config-wizard",
            "invite-reservations",
            "notification-dispatch",
            "system-email-delivery",
            "account-provisioning",
            "account-maintenance",
            "otp",
            "session-exchange",
            "appointment-sync",
            "appointment-cleanup",
            "matrix-agency",
            "runtime-policy");
    var settings = new TaskIdentityConfiguration();
    var tasks = new LinkedHashMap<String, TaskIdentityCredentials>();
    for (var task : TaskIdentity.values())
      tasks.put(
          task.key(),
          new TaskIdentityCredentials(
              "backend-" + task.key(), "test-" + task.key(), "sub-" + task.key()));
    settings.setTasks(tasks);
    settings.validate();
  }

  @Test
  void duplicateTaskCredentialsAbortStartupWithoutDisclosingThem() {
    var settings = new TaskIdentityConfiguration();
    var first = new TaskIdentityCredentials("wizard", "private-test-secret", "wizard-subject");
    var second = new TaskIdentityCredentials("mail", "private-test-secret", "mail-subject");
    var tasks = new LinkedHashMap<String, TaskIdentityCredentials>();
    for (var task : TaskIdentity.values()) {
      tasks.put(
          task.key(),
          new TaskIdentityCredentials(
              "backend-" + task.key(), "test-" + task.key(), "sub-" + task.key()));
    }
    tasks.put("config-wizard", first);
    tasks.put("notification-dispatch", second);
    settings.setTasks(tasks);
    assertThatThrownBy(settings::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("private-test-secret");
  }

  @Test
  void missingTaskDoesNotFallbackToAnotherIdentity() {
    var settings = new TaskIdentityConfiguration();
    settings.setTasks(
        Map.of("config-wizard", new TaskIdentityCredentials("wizard", "test-secret", "sub")));
    assertThatThrownBy(() -> settings.require(TaskIdentity.NOTIFICATION_DISPATCH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notification-dispatch");
  }

  @Test
  void eachRequiredTaskRetainsItsOwnConfiguredCredentials() {
    var settings = new TaskIdentityConfiguration();
    var tasks = new LinkedHashMap<String, TaskIdentityCredentials>();
    for (var task : TaskIdentity.values()) {
      tasks.put(
          task.key(),
          new TaskIdentityCredentials(
              "backend-" + task.key(), "test-" + task.key(), "sub-" + task.key()));
    }
    settings.setTasks(tasks);
    settings.validate();
    assertThat(settings.require(TaskIdentity.CONFIG_WIZARD).getClientId())
        .isEqualTo("backend-config-wizard");
    assertThat(settings.require(TaskIdentity.OTP).getClientId()).isEqualTo("backend-otp");
  }
}
