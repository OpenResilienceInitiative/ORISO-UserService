package de.caritas.cob.userservice.api.config;

import static org.junit.jupiter.api.Assertions.*;

import de.caritas.cob.userservice.api.service.matrixgroup.GroupMatrixPolicySettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The public application startup boundary, never a test of private URI/token helpers. */
class GroupMatrixPolicyConfigurationTest {
  @Test
  void enablingAnEncodedAliasInsteadOfTheExactHistoryResourceFailsStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(GroupMatrixPolicySettings.class)
        .withPropertyValues(
            "matrix.group.policy.enabled=true",
            "matrix.group.policy.token=synthetic-dedicated-membership-policy-credential",
            "matrix.group.participation-history.url=http://synapse.synthetic/oriso-internal/%67roup-participation-history")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void defaultDisabledDeploymentStartsWithoutACompanionEndpointOrCredential() {
    new ApplicationContextRunner()
        .withUserConfiguration(GroupMatrixPolicySettings.class)
        .run(context -> assertNull(context.getStartupFailure()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"MISSING_TOKEN", "INVALID_TOKEN", "MISSING_URL", "DIFFERENT_PATH", "PRIVATE_URL"})
  void enablingMissingOrInvalidDedicatedIntegrationFailsStartupWithoutPrivateCauses(
      String variant) {
    String token =
        variant.equals("MISSING_TOKEN")
            ? ""
            : variant.equals("INVALID_TOKEN")
                ? "synthetic-private-token"
                : "synthetic-dedicated-membership-policy-credential";
    String url =
        switch (variant) {
          case "MISSING_URL" -> "";
          case "DIFFERENT_PATH" -> "http://synapse.synthetic/_matrix/client/v3/sync";
          case "PRIVATE_URL" ->
              "http://synthetic-private-userinfo@synapse.synthetic/oriso-internal/group-participation-history";
          default -> "http://synapse.synthetic/oriso-internal/group-participation-history";
        };
    new ApplicationContextRunner()
        .withUserConfiguration(GroupMatrixPolicySettings.class)
        .withPropertyValues(
            "matrix.group.policy.enabled=true",
            "matrix.group.policy.token=" + token,
            "matrix.group.participation-history.url=" + url)
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              Throwable root = context.getStartupFailure();
              while (root.getCause() != null) root = root.getCause();
              assertEquals(
                  "Enabled Matrix group policy requires a dedicated credential and exact history endpoint",
                  root.getMessage());
              assertFalse(root.getMessage().contains("synthetic-private"));
            });
  }
}
