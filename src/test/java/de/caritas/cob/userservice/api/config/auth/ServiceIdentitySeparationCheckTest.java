package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import org.junit.jupiter.api.Test;

class ServiceIdentitySeparationCheckTest {
  @Test
  void taskOnlyStartupNeedsNoRetiredTechnicalOrAdminCredentials() {
    var human = new KeycloakCustomConfig();
    human.setAppClientId("app");
    var identities = mock(IdentityClientConfig.class);
    for (var task : TaskIdentity.values()) {
      when(identities.getTaskIdentity(task))
          .thenReturn(
              new TaskIdentityCredentials(
                  "backend-" + task.key(),
                  "synthetic-secret-" + task.key(),
                  "subject-" + task.key()));
    }
    assertThatCode(() -> configured(human, identities).verifyBackendClients())
        .doesNotThrowAnyException();
    verify(identities, never()).getTechnicalUser();
  }

  @Test
  void taskMayNotReuseHumanClientOrAnotherTaskCredential() {
    var human = new KeycloakCustomConfig();
    human.setAppClientId("app");
    var identities = mock(IdentityClientConfig.class);
    for (var task : TaskIdentity.values()) {
      when(identities.getTaskIdentity(task))
          .thenReturn(
              new TaskIdentityCredentials(
                  "backend-" + task.key(),
                  "synthetic-secret-" + task.key(),
                  "subject-" + task.key()));
    }
    when(identities.getTaskIdentity(TaskIdentity.OTP))
        .thenReturn(new TaskIdentityCredentials("app", "synthetic-secret-otp", "subject-otp"));
    assertThatThrownBy(() -> configured(human, identities).verifyBackendClients())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("synthetic-secret");
    when(identities.getTaskIdentity(TaskIdentity.OTP))
        .thenReturn(
            new TaskIdentityCredentials(
                "backend-otp", "synthetic-secret-account-maintenance", "subject-otp"));
    assertThatThrownBy(() -> configured(human, identities).verifyBackendClients())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("synthetic-secret");
  }

  @Test
  void incomingImporterMayNotReuseRuntimeTaskSubjectOrHumanClient() {
    var human = new KeycloakCustomConfig();
    human.setAppClientId("app");
    var identities = org.mockito.Mockito.mock(IdentityClientConfig.class);
    for (var task : TaskIdentity.values())
      when(identities.getTaskIdentity(task))
          .thenReturn(
              new TaskIdentityCredentials(
                  "backend-" + task.key(),
                  "synthetic-secret-" + task.key(),
                  "subject-" + task.key()));
    var check = configured(human, identities);
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "consultantImportClientId", "app");
    assertThatThrownBy(check::verifyBackendClients).isInstanceOf(IllegalStateException.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "consultantImportClientId", "backend-consultant-import");
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "consultantImportSubject", "subject-otp");
    assertThatThrownBy(check::verifyBackendClients).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void signingKeysCannotReuseAClientSecretOrEachOther() {
    var human = new KeycloakCustomConfig();
    human.setAppClientId("app");
    var identities = org.mockito.Mockito.mock(IdentityClientConfig.class);
    for (var task : TaskIdentity.values())
      when(identities.getTaskIdentity(task))
          .thenReturn(
              new TaskIdentityCredentials(
                  "backend-" + task.key(),
                  "synthetic-secret-" + task.key(),
                  "subject-" + task.key()));
    var check = configured(human, identities);
    String encoded =
        java.util.Base64.getEncoder()
            .encodeToString(
                "maintenance-synthetic-key-32bytes"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "provisioningOriginKey", encoded);
    assertThatThrownBy(check::verifyBackendClients)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(encoded);
    check = configured(human, identities);
    when(identities.getTaskIdentity(TaskIdentity.OTP))
        .thenReturn(new TaskIdentityCredentials("backend-otp", encoded, "subject-otp"));
    assertThatThrownBy(check::verifyBackendClients)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(encoded);
  }

  @Test
  void wizardPolicyKeyCannotReuseAnOriginKeyOrTaskSecret() {
    var human = new KeycloakCustomConfig();
    human.setAppClientId("app");
    var identities = mock(IdentityClientConfig.class);
    for (var task : TaskIdentity.values())
      when(identities.getTaskIdentity(task))
          .thenReturn(
              new TaskIdentityCredentials(
                  "backend-" + task.key(),
                  "synthetic-secret-" + task.key(),
                  "subject-" + task.key()));
    var check = configured(human, identities);
    String reused =
        java.util.Base64.getEncoder()
            .encodeToString(
                "maintenance-synthetic-key-32bytes"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "wizardPolicyContextKey", reused);
    assertThatThrownBy(check::verifyBackendClients)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(reused);
    check = configured(human, identities);
    reused =
        java.util.Base64.getEncoder()
            .encodeToString(
                "wizard-policy-synthetic-key-32bytes"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(identities.getTaskIdentity(TaskIdentity.OTP))
        .thenReturn(new TaskIdentityCredentials("backend-otp", reused, "subject-otp"));
    assertThatThrownBy(check::verifyBackendClients)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(reused);
  }

  private ServiceIdentitySeparationCheck configured(
      KeycloakCustomConfig human, IdentityClientConfig identities) {
    var check = new ServiceIdentitySeparationCheck(human, identities);
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "consultantImportClientId", "backend-consultant-import");
    org.springframework.test.util.ReflectionTestUtils.setField(
        check, "consultantImportSubject", "import-service-subject");
    org.springframework.test.util.ReflectionTestUtils.setField(
        check,
        "provisioningOriginKey",
        java.util.Base64.getEncoder()
            .encodeToString(
                "provisioning-synthetic-key-32byte"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        check,
        "maintenanceOriginKey",
        java.util.Base64.getEncoder()
            .encodeToString(
                "maintenance-synthetic-key-32bytes"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    org.springframework.test.util.ReflectionTestUtils.setField(
        check,
        "wizardPolicyContextKey",
        java.util.Base64.getEncoder()
            .encodeToString(
                "wizard-policy-synthetic-key-32bytes"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return check;
  }
}
