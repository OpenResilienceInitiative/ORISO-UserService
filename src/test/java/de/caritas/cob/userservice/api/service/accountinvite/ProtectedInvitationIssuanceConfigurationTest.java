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
    assertThat(new ProtectedInvitationIssuanceConfiguration(installed())
        .protectedInvitationProfiles()).isEqualTo(new ProtectedInvitationProfiles(
            "actual-native-realm", Map.of(AccountInviteTargetRole.TENANT_ADMIN, "tenant-owner",
                AccountInviteTargetRole.PLATFORM_ADMIN, "platform-v1")));
  }

  @Test
  void refusesUnresolvedRealmOrNonScenarioCModelInsteadOfGuessingABinding() {
    for (String value : new String[] {"", " ", "${KEYCLOAK_REALM_INTERNAL_ID}"}) {
      var environment = installed().withProperty("KEYCLOAK_REALM_INTERNAL_ID", value);
      assertThatThrownBy(() -> new ProtectedInvitationIssuanceConfiguration(environment)
          .protectedInvitationProfiles()).isInstanceOf(IllegalStateException.class);
    }
    for (String model : new String[] {"LEGACY", "", "SIX_ACTORS", "five_actors"}) {
      var environment = installed().withProperty("TASK_IDENTITY_MODEL", model);
      assertThatThrownBy(() -> new ProtectedInvitationIssuanceConfiguration(environment)
          .protectedInvitationProfiles()).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void refusesDuplicateTrailingWrongTypeOrUnknownProfileMappingInput() {
    for (String mapping : new String[] {
        "null", "[]", "{}", REVIEWED + " {}", REVIEWED + " true",
        "{\"TENANT_ADMIN\":\"tenant-owner\",\"TENANT_ADMIN\":\"other\","
            + "\"PLATFORM_ADMIN\":\"platform-v1\"}",
        "{\"TENANT_ADMIN\":true,\"PLATFORM_ADMIN\":\"platform-v1\"}",
        "{\"TENANT_ADMIN\":[\"tenant-owner\"],\"PLATFORM_ADMIN\":\"platform-v1\"}",
        "{\"TENANT_ADMIN\":\"tenant-owner\",\"PLATFORM_ADMIN\":\"platform-v1\","
            + "\"COUNSELLOR\":\"ordinary\"}",
        "{\"tenant_admin\":\"tenant-owner\",\"PLATFORM_ADMIN\":\"platform-v1\"}"
    }) {
      var environment = installed().withProperty("ORISO_PROTECTED_INVITATION_PROFILE_IDS", mapping);
      assertThatThrownBy(() -> new ProtectedInvitationIssuanceConfiguration(environment)
          .protectedInvitationProfiles()).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void refusesMissingOrOversizedMappingWithAFixedNonSecretFailure() {
    for (String mapping : new String[] {"", " ", "x".repeat(4097)}) {
      var environment = installed().withProperty("ORISO_PROTECTED_INVITATION_PROFILE_IDS", mapping);
      assertThatThrownBy(() -> new ProtectedInvitationIssuanceConfiguration(environment)
          .protectedInvitationProfiles()).isInstanceOf(IllegalStateException.class)
          .hasMessage("Configure the reviewed protected invitation profile bindings");
    }
    var missing = installed();
    missing.getPropertySources().remove("mockProperties");
    assertThatThrownBy(() -> new ProtectedInvitationIssuanceConfiguration(missing)
        .protectedInvitationProfiles()).isInstanceOf(IllegalStateException.class);
  }

  private static MockEnvironment installed() {
    return new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
        .withProperty("KEYCLOAK_REALM_INTERNAL_ID", "actual-native-realm")
        .withProperty("ORISO_PROTECTED_INVITATION_PROFILE_IDS", REVIEWED);
  }
}
