package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Installed metadata only; constructing it never represents native human approval. */
class ProtectedInvitationProfilesTest {
  @Test
  void preservesReviewedProfileIdsAndDefensivelyFreezesTheInstalledMap() {
    var selected = new HashMap<>(reviewed());
    var profiles = new ProtectedInvitationProfiles("actual-native-realm", selected);
    selected.put(AccountInviteTargetRole.TENANT_ADMIN, "another-profile");
    assertThat(profiles.realmId()).isEqualTo("actual-native-realm");
    assertThat(profiles.profileIds()).isEqualTo(reviewed());
    assertThatThrownBy(() -> profiles.profileIds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void refusesMissingOrUnboundedNativeRealmBinding() {
    for (String realm : new String[] {null, "", " ", "x".repeat(256), "native\nrealm"}) {
      assertThatThrownBy(() -> new ProtectedInvitationProfiles(realm, reviewed()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void onlyTheTwoProtectedKindsMaySelectAnInstalledProfile() {
    assertThatThrownBy(() -> new ProtectedInvitationProfiles("actual-native-realm", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProtectedInvitationProfiles("actual-native-realm",
        Map.of(AccountInviteTargetRole.TENANT_ADMIN, "tenant-reviewed")))
        .isInstanceOf(IllegalArgumentException.class);
    var ordinary = new HashMap<>(reviewed());
    ordinary.put(AccountInviteTargetRole.COUNSELLOR, "ordinary-profile");
    assertThatThrownBy(() -> new ProtectedInvitationProfiles("actual-native-realm", ordinary))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesBlankUnboundedOrSharedCrossKindProfileIds() {
    for (String id : new String[] {"", " ", "x".repeat(256)}) {
      var invalid = new HashMap<>(reviewed());
      invalid.put(AccountInviteTargetRole.TENANT_ADMIN, id);
      assertThatThrownBy(() -> new ProtectedInvitationProfiles("actual-native-realm", invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ProtectedInvitationProfiles("actual-native-realm",
        Map.of(AccountInviteTargetRole.TENANT_ADMIN, "shared",
            AccountInviteTargetRole.PLATFORM_ADMIN, "shared")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<AccountInviteTargetRole, String> reviewed() {
    return Map.of(AccountInviteTargetRole.TENANT_ADMIN, "tenant-reviewed",
        AccountInviteTargetRole.PLATFORM_ADMIN, "platform-reviewed");
  }
}
