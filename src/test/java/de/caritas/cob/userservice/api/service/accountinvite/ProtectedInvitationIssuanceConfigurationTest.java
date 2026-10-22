package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProtectedInvitationIssuanceConfigurationTest {
  private static final String REVIEWED =
      "{\"TENANT_ADMIN\":\"tenant-owner\",\"PLATFORM_ADMIN\":\"platform-v1\"}";

  @Test
  void readsOnlyExplicitInstalledRealmAndTheTwoReviewedProfileIds() {
    assertThat(
            new ProtectedInvitationIssuanceConfiguration(installed()).protectedInvitationProfiles())
        .isEqualTo(
            new ProtectedInvitationProfiles(
                "actual-native-realm",
                Map.of(
                    AccountInviteTargetRole.TENANT_ADMIN,
                    "tenant-owner",
                    AccountInviteTargetRole.PLATFORM_ADMIN,
                    "platform-v1")));
  }

  @Test
  void refusesUnresolvedRealmOrNonScenarioCModelInsteadOfGuessingABinding() {
    for (String value : new String[] {"", " ", "${KEYCLOAK_REALM_INTERNAL_ID}"}) {
      var environment = installed().withProperty("KEYCLOAK_REALM_INTERNAL_ID", value);
      assertThatThrownBy(
              () ->
                  new ProtectedInvitationIssuanceConfiguration(environment)
                      .protectedInvitationProfiles())
          .isInstanceOf(IllegalStateException.class);
    }
    for (String model : new String[] {"LEGACY", "", "SIX_ACTORS", "five_actors"}) {
      var environment = installed().withProperty("TASK_IDENTITY_MODEL", model);
      assertThatThrownBy(
              () ->
                  new ProtectedInvitationIssuanceConfiguration(environment)
                      .protectedInvitationProfiles())
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void refusesDuplicateTrailingWrongTypeOrUnknownProfileMappingInput() {
    for (String mapping :
        new String[] {
          "null",
          "[]",
          "{}",
          REVIEWED + " {}",
          REVIEWED + " true",
          "{\"TENANT_ADMIN\":\"tenant-owner\",\"TENANT_ADMIN\":\"other\","
              + "\"PLATFORM_ADMIN\":\"platform-v1\"}",
          "{\"TENANT_ADMIN\":true,\"PLATFORM_ADMIN\":\"platform-v1\"}",
          "{\"TENANT_ADMIN\":[\"tenant-owner\"],\"PLATFORM_ADMIN\":\"platform-v1\"}",
          "{\"TENANT_ADMIN\":\"tenant-owner\",\"PLATFORM_ADMIN\":\"platform-v1\","
              + "\"COUNSELLOR\":\"ordinary\"}",
          "{\"tenant_admin\":\"tenant-owner\",\"PLATFORM_ADMIN\":\"platform-v1\"}"
        }) {
      var environment = installed().withProperty("ORISO_PROTECTED_INVITATION_PROFILE_IDS", mapping);
      assertThatThrownBy(
              () ->
                  new ProtectedInvitationIssuanceConfiguration(environment)
                      .protectedInvitationProfiles())
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void refusesMissingOrOversizedMappingWithAFixedNonSecretFailure() {
    for (String mapping : new String[] {"", " ", "x".repeat(4097)}) {
      var environment = installed().withProperty("ORISO_PROTECTED_INVITATION_PROFILE_IDS", mapping);
      assertThatThrownBy(
              () ->
                  new ProtectedInvitationIssuanceConfiguration(environment)
                      .protectedInvitationProfiles())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Configure the reviewed protected invitation profile bindings");
    }
    var missing = installed();
    missing.getPropertySources().remove("mockProperties");
    assertThatThrownBy(
            () ->
                new ProtectedInvitationIssuanceConfiguration(missing).protectedInvitationProfiles())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void explicitScenarioCConfigurationPublishesTheCompleteFixedRecoveryGraph() {
    var keycloak = org.mockito.Mockito.mock(de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig.class);
    org.mockito.Mockito.when(keycloak.getRealm()).thenReturn("oriso");
    org.mockito.Mockito.when(keycloak.getAuthServerUrl()).thenReturn("https://identity.example.test/auth");
    var source = new org.h2.jdbcx.JdbcDataSource();
    source.setURL("jdbc:h2:mem:runtime-" + java.util.UUID.randomUUID() + ";MODE=MySQL");
    var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
    var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(ProtectedInvitationIssuanceConfiguration.class)
        .withPropertyValues("ORISO_ACCOUNT_LIFECYCLE_ENABLED=true", "TASK_IDENTITY_MODEL=FIVE_ACTORS",
            "KEYCLOAK_REALM_INTERNAL_ID=aa6e019d-2ec2-4c4e-ab80-5fd3b9b58294",
            "ORISO_ACCOUNT_LIFECYCLE_REALM=oriso", "ORISO_PROTECTED_INVITATION_PROFILE_IDS=" + REVIEWED,
            "ORISO_TASK_CONTEXT_KEY=" + java.util.Base64.getEncoder().encodeToString(new byte[32]))
        .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> new org.springframework.jdbc.core.JdbcTemplate(source))
        .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> manager)
        .withBean(de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec.class,
            () -> new de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec(new byte[32]))
        .withBean(de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig.class, () -> keycloak)
        .withBean("keycloakRestTemplate", org.springframework.web.client.RestTemplate.class, org.springframework.web.client.RestTemplate::new);
    for (Class<?> type : new Class<?>[] {
        jakarta.persistence.EntityManager.class, jakarta.servlet.http.HttpServletRequest.class,
        de.caritas.cob.userservice.api.helper.AuthenticatedUser.class,
        de.caritas.cob.userservice.api.helper.UsernameTranscoder.class,
        de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration.class,
        de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker.class,
        de.caritas.cob.userservice.api.port.out.AccountInviteRepository.class,
        de.caritas.cob.userservice.api.port.out.AdminRepository.class,
        de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository.class,
        de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository.class,
        de.caritas.cob.userservice.api.port.out.IdentityClientConfig.class,
        de.caritas.cob.userservice.api.port.out.IdentityAuthentication.class,
        de.caritas.cob.userservice.api.tenant.TenantContextProvider.class,
        de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.class,
        de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.class,
        de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService.class,
        AccountInviteService.class,
        de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient.class,
        de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient.class
    }) runner = registerMock(runner, type);
    runner.run(context -> {
      assertThat(context.getStartupFailure()).isNull();
      assertThat(context).hasSingleBean(de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedCleanupStatusCodec.class);
      assertThat(context).hasSingleBean(ProtectedInvitationRecoverySweep.class);
    });
  }

  private static <T> org.springframework.boot.test.context.runner.ApplicationContextRunner registerMock(
      org.springframework.boot.test.context.runner.ApplicationContextRunner runner, Class<T> type) {
    return runner.withBean(type, () -> org.mockito.Mockito.mock(type));
  }

  private static MockEnvironment installed() {
    return new MockEnvironment()
        .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
        .withProperty("KEYCLOAK_REALM_INTERNAL_ID", "actual-native-realm")
        .withProperty("ORISO_PROTECTED_INVITATION_PROFILE_IDS", REVIEWED);
  }
}
