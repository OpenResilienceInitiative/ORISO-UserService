package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

class AccountLifecycleCallerConfigurationTest {
  private MockEnvironment valid() {
    return new MockEnvironment()
        .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
        .withProperty(
            "ORISO_ACCOUNT_LIFECYCLE_GROUPS",
            "{\"/human-roles/user\":[\"user\"],\"/human-roles/consultant\":[\"consultant\"]}")
        .withProperty("ORISO_IDENTITY_HUMAN_CLIENT_IDS", "[\"human-admin\"]")
        .withProperty(
            "ORISO_TASK_CONTEXT_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));
  }

  @Test
  void defaultOffDoesNotCreateAnyCallerOrSchedulerWithoutBindings() {
    new ApplicationContextRunner()
        .withUserConfiguration(AccountLifecycleCallerConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(0, context.getBeansOfType(OrdinaryAccountCreation.class).size());
              assertEquals(0, context.getBeansOfType(AccountLifecycleRetryScheduler.class).size());
              assertEquals(0, context.getBeansOfType(SelfAppOtpMaintenance.class).size());
            });
  }

  @Test
  void enabledCallerGraphInstallsThePublicSelfOtpOwnerWithoutMakingAnRpc() {
    var actors = mock(TaskActorConfiguration.class);
    when(actors.require(any(TaskPurpose.class)))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_ADMIN,
                "backend-account-admin",
                "fixture-only-secret",
                "33333333-3333-4333-8333-333333333333"));
    var mode = mock(IdentityClientConfig.class);
    when(mode.isFiveActorMode()).thenReturn(true);
    var keycloak = mock(KeycloakConfig.class);
    when(keycloak.getRealm()).thenReturn("fixture");
    when(keycloak.getAuthServerUrl()).thenReturn("https://identity.fixture.invalid");
    var http = mock(RestTemplate.class);
    var broker = mock(TaskActorTokenBroker.class);
    var jdbc = mock(JdbcTemplate.class);
    new ApplicationContextRunner()
        .withInitializer(
            context -> {
              context.getEnvironment().merge(valid());
              context.addBeanFactoryPostProcessor(
                  factory -> {
                    factory.registerSingleton("fixtureKeycloakConfiguration", keycloak);
                    factory.registerSingleton("keycloakRestTemplate", http);
                    factory.registerSingleton("fixtureTaskActors", actors);
                    factory.registerSingleton("fixtureTaskBroker", broker);
                    factory.registerSingleton("fixtureIdentityMode", mode);
                    factory.registerSingleton("fixtureHuman", new AuthenticatedUser());
                    factory.registerSingleton("fixtureRequest", new MockHttpServletRequest());
                    factory.registerSingleton("fixtureJdbc", jdbc);
                    factory.registerSingleton(
                        "fixtureTransactions", mock(PlatformTransactionManager.class));
                    factory.registerSingleton("fixtureMatrix", mock(MatrixSynapseService.class));
                    factory.registerSingleton(
                        "fixtureMatrixConfiguration", mock(MatrixConfig.class));
                    factory.registerSingleton(
                        "fixtureAppointments", mock(AppointmentService.class));
                    factory.registerSingleton(
                        "fixtureTenants",
                        mock(
                            de.caritas.cob.userservice.api.admin.service.tenant.TenantService
                                .class));
                    factory.registerSingleton(
                        "fixtureAgencies",
                        mock(de.caritas.cob.userservice.api.service.agency.AgencyService.class));
                  });
            })
        .withPropertyValues(
            "ORISO_ACCOUNT_LIFECYCLE_ENABLED=true",
            "ORISO_ACCOUNT_LIFECYCLE_REALM=fixture",
            "KEYCLOAK_REALM_INTERNAL_ID=22222222-2222-4222-8222-222222222222")
        .withUserConfiguration(AccountLifecycleCallerConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(1, context.getBeansOfType(OrdinaryAccountCreation.class).size());
              assertEquals(
                  1,
                  context.getBeansOfType(SelfAppOtpMaintenance.class).size(),
                  "The enabled production caller graph must register the existing public OTP owner");
              assertTrue(context.getBean(SelfAppOtpMaintenance.class).isFiveActorMode());
              verifyNoInteractions(http, broker, jdbc);
            });
  }

  @Test
  void legacyModeCannotInstallTheBoundedFiveActorCaller() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new AccountLifecycleCallerConfiguration(
                valid().withProperty("TASK_IDENTITY_MODEL", "LEGACY")));
  }

  @Test
  void unknownOrDuplicateOrdinaryRoleProfilesAndWildcardHumansFailClosed() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new AccountLifecycleCallerConfiguration(
                valid()
                    .withProperty(
                        "ORISO_ACCOUNT_LIFECYCLE_GROUPS",
                        "{\"/human-roles/a\":[\"user\"],\"/human-roles/b\":[\"user\"]}")));
    assertThrows(
        IllegalStateException.class,
        () ->
            new AccountLifecycleCallerConfiguration(
                valid()
                    .withProperty(
                        "ORISO_ACCOUNT_LIFECYCLE_GROUPS",
                        "{\"/human-roles/admin\":[\"tenant-admin\"]}")));
    assertThrows(
        IllegalStateException.class,
        () ->
            new AccountLifecycleCallerConfiguration(
                valid().withProperty("ORISO_IDENTITY_HUMAN_CLIENT_IDS", "[\"*\"]")));
  }

  @Test
  void oneSharedKeyNeedsAtLeast32DecodedBytes() {
    var config = new AccountLifecycleCallerConfiguration(valid());
    assertNotNull(config.accountLifecycleCodec());
    var shortKey =
        new AccountLifecycleCallerConfiguration(
            valid()
                .withProperty(
                    "ORISO_TASK_CONTEXT_KEY",
                    java.util.Base64.getEncoder().encodeToString(new byte[31])));
    assertThrows(IllegalStateException.class, shortKey::accountLifecycleCodec);
  }
}
