package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

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
