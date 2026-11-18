package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES;
import static de.caritas.cob.userservice.api.model.GuestAccountMode.PERMANENT;
import static de.caritas.cob.userservice.api.model.GuestAccountMode.TEMPORARY;
import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.GuestAccountMode;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #1662: a mode that is not allowed any more falls back to the closest mode that still is. */
class GuestAccountModePolicyTest {

  private static GuestAccountModePolicy allowing(GuestAccountMode... modes) {
    Set<GuestAccountMode> allowed =
        modes.length == 0 ? EnumSet.noneOf(GuestAccountMode.class) : EnumSet.of(modes[0], modes);
    return (tenant, agency, medium) -> allowed;
  }

  @Test
  void anAllowedModeStays() {
    assertThat(allowing(TEMPORARY, PERMANENT).cap(1L, 2L, InviteLinkChatType.CHAT, TEMPORARY))
        .isEqualTo(TEMPORARY);
  }

  @Test
  void aForbiddenModeFallsBackToPermanentWhileThatIsAllowed() {
    assertThat(allowing(PERMANENT).cap(1L, 2L, InviteLinkChatType.CHAT, GUEST_CHOOSES))
        .isEqualTo(PERMANENT);
  }

  @Test
  void aTraegerWithoutPermanentGuestAccountsFallsBackToTheGuestsChoice() {
    assertThat(allowing(TEMPORARY, GUEST_CHOOSES).cap(1L, 2L, InviteLinkChatType.VIDEO, PERMANENT))
        .isEqualTo(GUEST_CHOOSES);
  }

  @Test
  void aTraegerWithOnlyTemporaryGuestAccountsFallsBackToThem() {
    assertThat(allowing(TEMPORARY).cap(1L, 2L, InviteLinkChatType.AUDIO, PERMANENT))
        .isEqualTo(TEMPORARY);
  }

  @Test
  void nothingAllowedStillNeverCreatesAnAccountOrisoDeletes() {
    assertThat(GuestAccountModePolicy.fallbackWithin(Set.of(), TEMPORARY)).isEqualTo(PERMANENT);
  }
}
