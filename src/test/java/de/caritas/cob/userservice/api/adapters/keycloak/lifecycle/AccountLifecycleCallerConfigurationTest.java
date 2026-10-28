package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;

class AccountLifecycleCallerConfigurationTest {
  private MockEnvironment valid() {
    return new MockEnvironment()
        .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
        .withProperty(
            "ORISO_ACCOUNT_LIFECYCLE_GROUPS",
            "{\"/human-roles/user\":[\"user\"],\"/human-roles/consultant\":[\"consultant\"],\"/human-roles/agency\":[\"restricted-agency-admin\",\"user-admin\"]}")
        .withProperty("ORISO_ACCOUNT_MAINTENANCE_CONFIG", ordinaryPolicy())
        .withProperty("ORISO_PROTECTED_INVITATIONS_CONFIG", protectedPolicy())
        .withProperty("ORISO_IDENTITY_HUMAN_CLIENT_IDS", "[\"human-admin\"]")
        .withProperty(
            "ORISO_TASK_CONTEXT_KEY", java.util.Base64.getEncoder().encodeToString(new byte[32]));
  }

  private String ordinaryPolicy() {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var root = json.createObjectNode().put("version", 1);
    root.putObject("native");
    var profiles = root.putArray("ordinaryProfiles");
    java.util.Map.of(
            "user", java.util.Set.of("user"),
            "consultant", java.util.Set.of("consultant"),
            "agency", java.util.Set.of("restricted-agency-admin", "user-admin"))
        .forEach(
            (id, roles) -> {
              var profile = profiles.addObject().put("id", id).put("version", 3);
              profile.put("groupId", "group-" + id).put("groupParentId", "human-root");
              profile.put("groupPath", "/human-roles/" + id).put("allowUnassignedTenant", false);
              profile.set("realmRoles", json.valueToTree(new java.util.TreeSet<>(roles)));
              profile.putObject("clientRoles");
              profile.putArray("selfOperations").add("account.read").add("account.password");
            });
    return root.toString();
  }

  private String protectedPolicy() {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var root =
        json.createObjectNode()
            .put("realmId", "22222222-2222-4222-8222-222222222222")
            .put("realmName", "fixture");
    root.putArray("technicalRoles").add("technical");
    var profiles = root.putArray("profiles");
    java.util.Map.of(
            "tenant", java.util.Set.of("tenant-admin"),
            "platform", java.util.Set.of("agency-admin", "tenant-admin"))
        .forEach(
            (id, roles) -> {
              var profile = profiles.addObject().put("id", id).put("version", 7);
              profile.put("kind", id.equals("tenant") ? "TENANT_ADMIN" : "PLATFORM_ADMIN");
              profile.putArray("tenantModes").add("EXISTING");
              profile.put("groupId", "group-" + id).put("groupPath", "/human-roles/" + id);
              profile.set("realmRoles", json.valueToTree(new java.util.TreeSet<>(roles)));
              profile.putObject("clientRoles");
              profile.put("usernamePolicy", "USERNAME_TRANSCODER_V1");
              profile.put("firstNamePolicy", "INVITE_WITH_EMAIL_LOCAL_PART_FALLBACK");
              profile.put("lastNamePolicy", "INVITE_WITH_ADMIN_FALLBACK");
            });
    return root.toString();
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
              assertEquals(0, context.getBeansOfType(SelfPasswordMaintenance.class).size());
              assertEquals(
                  0,
                  context
                      .getBeansOfType(
                          de.caritas.cob.userservice.api.adapters.keycloak.maintenance
                              .SelfLocaleMaintenance.class)
                      .size());
              assertEquals(
                  0,
                  context
                      .getBeansOfType(
                          de.caritas.cob.userservice.api.adapters.persistence.password
                              .JdbcExistingSetupPasswordClaims.class)
                      .size());
            });
  }

  private void withEnabledCallerGraph(
      java.util.function.Consumer<org.springframework.context.ApplicationContext> assertions) {
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
    var source = mock(javax.sql.DataSource.class);
    when(jdbc.getDataSource()).thenReturn(source);
    var entities = mock(jakarta.persistence.EntityManager.class);
    var entityFactory = mock(jakarta.persistence.EntityManagerFactory.class);
    when(entities.isOpen()).thenReturn(true);
    when(entities.getEntityManagerFactory()).thenReturn(entityFactory);
    var transactions = new org.springframework.orm.jpa.JpaTransactionManager(entityFactory);
    transactions.setDataSource(source);
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
                    factory.registerSingleton(
                        "fixturePasswordValidation",
                        mock(de.caritas.cob.userservice.api.port.in.IdentityManaging.class));
                    factory.registerSingleton("fixtureRequest", new MockHttpServletRequest());
                    factory.registerSingleton("fixtureJdbc", jdbc);
                    factory.registerSingleton("fixtureTransactions", transactions);
                    factory.registerSingleton("fixtureEntities", entities);
                    factory.registerSingleton(
                        "fixtureDeletionConsumer",
                        mock(
                            de.caritas.cob.userservice.api.workflow.delete.service
                                .DeleteUserAccountService.class));
                    factory.registerSingleton("fixtureAdminScope", mock(AdminScope.class));
                    factory.registerSingleton(
                        "fixtureConsultantEligibility",
                        mock(
                            de.caritas.cob.userservice.api.admin.service.agency
                                .ConsultantAgencyDeletionValidationService.class));
                    factory.registerSingleton(
                        "fixtureReactivationSaga",
                        mock(
                            de.caritas.cob.userservice.api.admin.service
                                .IdentityReactivationSagaStore.class));
                    factory.registerSingleton(
                        "fixtureDeletionClaims",
                        mock(
                            de.caritas.cob.userservice.api.workflow.scheduling
                                .ScheduledTaskClaimService.class));
                    factory.registerSingleton(
                        "fixtureDeletionLifecycle",
                        mock(
                            de.caritas.cob.userservice.api.workflow.delete.service
                                .DeletionLifecycleService.class));
                    factory.registerSingleton(
                        "fixtureProtectedProfiles",
                        new de.caritas.cob.userservice.api.service.accountinvite
                            .ProtectedInvitationProfiles(
                            "22222222-2222-4222-8222-222222222222",
                            java.util.Map.of(
                                de.caritas.cob.userservice.api.service.accountinvite
                                        .AccountInviteTargetRole.TENANT_ADMIN,
                                    "tenant",
                                de.caritas.cob.userservice.api.service.accountinvite
                                        .AccountInviteTargetRole.PLATFORM_ADMIN,
                                    "platform")));
                    factory.registerSingleton(
                        "fixtureAccountInactivity",
                        mock(
                            de.caritas.cob.userservice.api.workflow.accountinactivity
                                .AccountInactivityService.class));
                    factory.registerSingleton(
                        "fixtureAccountInactivityEffects",
                        mock(
                            de.caritas.cob.userservice.api.workflow.accountinactivity
                                .DefaultAccountInactivityEffects.class));
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
              assertions.accept(context);
              verify(jdbc, atLeastOnce()).getDataSource();
              verifyNoMoreInteractions(jdbc);
              verifyNoInteractions(http, broker, source);
            });
  }

  @Test
  void enabledCallerGraphInstallsThePublicSelfOtpOwnerWithoutMakingAnRpc() {
    withEnabledCallerGraph(
        context -> {
          assertEquals(1, context.getBeansOfType(OrdinaryAccountCreation.class).size());
          assertEquals(
              1,
              context.getBeansOfType(SelfAppOtpMaintenance.class).size(),
              "The enabled production caller graph must register the existing public OTP owner");
          assertTrue(context.getBean(SelfAppOtpMaintenance.class).isFiveActorMode());
          assertEquals(
              1,
              context.getBeansOfType(HumanAdminAccountMaintenance.class).size(),
              "The actual opt-in caller graph must register scoped public Admin maintenance");
        });
  }

  @Test
  void enabledCallerGraphAlsoInstallsPasswordLocaleAndOwnedSetupClaims() {
    withEnabledCallerGraph(
        context ->
            assertAll(
                () -> assertEquals(1, context.getBeansOfType(SelfPasswordMaintenance.class).size()),
                () ->
                    assertEquals(
                        1,
                        context
                            .getBeansOfType(
                                de.caritas.cob.userservice.api.adapters.keycloak.maintenance
                                    .SelfLocaleMaintenance.class)
                            .size()),
                () ->
                    assertEquals(
                        1,
                        context
                            .getBeansOfType(
                                de.caritas.cob.userservice.api.adapters.persistence.password
                                    .JdbcExistingSetupPasswordClaims.class)
                            .size())));
  }

  @Test
  void enabledCallerGraphJoinsTheExistingInactivityServiceToItsFiniteTransport() {
    withEnabledCallerGraph(
        context -> {
          assertEquals(
              1,
              context
                  .getBeansOfType(
                      de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport
                          .class)
                  .size());
          var transport =
              context.getBean(
                  de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport.class);
          var existing =
              context.getBean(
                  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
                      .class);
          verify(existing)
              .configureScopedInactivity(
                  same(transport),
                  same(context.getBean(AuthenticatedUser.class)),
                  same(context.getBean(jakarta.servlet.http.HttpServletRequest.class)),
                  eq("https://identity.fixture.invalid/realms/fixture"),
                  eq(java.util.Set.of("human-admin")));
        });
  }

  @Test
  void enabledCallerUsesTheSameReviewedCatalogForAllFivePasswordKinds() {
    withEnabledCallerGraph(
        context -> {
          assertEquals(1, context.getBeansOfType(ReviewedMaintenanceProfiles.class).size());
          var catalog = context.getBean(ReviewedMaintenanceProfiles.class).profiles();
          assertEquals(
              java.util.Set.of(
                  "USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN"),
              catalog.keySet());
          assertEquals(3, catalog.get("USER").get(0).version());
          assertEquals(7, catalog.get("PLATFORM_ADMIN").get(0).version());
          assertEquals(1, context.getBeansOfType(ClaimedPasswordMaintenance.class).size());
          context.getBean(ClaimedPasswordMaintenance.class).requireSupport();
        });
  }

  @Test
  void enabledCallerInstallsTheExistingScopedScheduledDeletionOwner() {
    withEnabledCallerGraph(
        context ->
            assertEquals(
                1,
                context
                    .getBeansOfType(
                        de.caritas.cob.userservice.api.workflow.delete.service
                            .ScheduledAskerMaintenance.class)
                    .size(),
                "FIVE_ACTORS must install the existing deletion owner, not only refuse legacy deletion"));
  }

  @Test
  void enabledCallerInstallsTheScopedConsultantDeletionOwner() {
    withEnabledCallerGraph(
        context ->
            assertEquals(
                1,
                context
                    .getBeansOfType(
                        de.caritas.cob.userservice.api.workflow.delete.service
                            .ScheduledConsultantMaintenance.class)
                    .size(),
                "FIVE_ACTORS must install the consultant owner as well as the asker owner"));
  }

  @Test
  void enabledCallerInstallsTheScopedAskerReactivationOwner() {
    withEnabledCallerGraph(
        context ->
            assertEquals(
                1,
                context.getBeansOfType(ScopedAskerReactivation.class).size(),
                "FIVE_ACTORS must install the bounded chosen-password reactivation owner"));
  }

  @Test
  void enabledCallerInstallsThePublicPreDeletionOwnerWithoutAnyPasswordOrNativeCall() {
    withEnabledCallerGraph(
        context -> {
          var owners =
              context.getBeansOfType(
                  de.caritas.cob.userservice.api.workflow.delete.service
                      .PublicPreDeletionMaintenance.class);
          assertEquals(1, owners.size());
          assertTrue(owners.values().iterator().next().isFiveActorMode());
          verifyNoInteractions(
              context.getBean(de.caritas.cob.userservice.api.port.in.IdentityManaging.class));
        });
  }

  @Test
  void defaultOffDoesNotInstallPublicPreDeletionWithoutAnyBindings() {
    new ApplicationContextRunner()
        .withUserConfiguration(AccountLifecycleCallerConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(
                  context
                      .getBeansOfType(
                          de.caritas.cob.userservice.api.workflow.delete.service
                              .PublicPreDeletionMaintenance.class)
                      .isEmpty());
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
