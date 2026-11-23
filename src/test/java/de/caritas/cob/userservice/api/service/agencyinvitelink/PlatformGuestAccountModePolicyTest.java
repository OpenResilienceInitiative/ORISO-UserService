package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.GuestAccountMode;
import org.junit.jupiter.api.Test;

class PlatformGuestAccountModePolicyTest {

  @Test
  void everyModeIsOfferedWhileThePlatformAllowsTemporaryAccounts() {
    var policy = new PlatformGuestAccountModePolicy((tenant, agency, medium) -> true);

    assertThat(policy.allowedModes(1L, 2L, InviteLinkChatType.VIDEO))
        .containsExactlyInAnyOrder(GuestAccountMode.values());
    assertThat(policy.cap(1L, 2L, InviteLinkChatType.VIDEO, GuestAccountMode.TEMPORARY))
        .isEqualTo(GuestAccountMode.TEMPORARY);
  }

  @Test
  void onlyPermanentAccountsWhileThePlatformForbidsTemporaryOnes() {
    var policy = new PlatformGuestAccountModePolicy((tenant, agency, medium) -> false);

    assertThat(policy.allowedModes(1L, 2L, InviteLinkChatType.CHAT))
        .containsExactly(GuestAccountMode.PERMANENT);
    assertThat(policy.allows(1L, 2L, InviteLinkChatType.CHAT, GuestAccountMode.GUEST_CHOOSES))
        .isFalse();
    assertThat(policy.cap(1L, 2L, InviteLinkChatType.CHAT, GuestAccountMode.GUEST_CHOOSES))
        .as("a link set before the cap creates no temporary accounts any more")
        .isEqualTo(GuestAccountMode.PERMANENT);
  }
}
