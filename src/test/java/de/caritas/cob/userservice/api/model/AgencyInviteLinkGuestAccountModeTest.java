package de.caritas.cob.userservice.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationAccessMode;
import org.junit.jupiter.api.Test;

/** #1662: the guest account mode of a link, and the two older fields that mirror it. */
class AgencyInviteLinkGuestAccountModeTest {

  @Test
  void applyGuestAccountModeWritesTheMirrorsAnOlderReleaseReads() {
    var link = AgencyInviteLink.builder().linkKind("COUNSELLOR").build();

    link.applyGuestAccountMode(GuestAccountMode.TEMPORARY);
    assertThat(link.getGuestAccountMode()).isEqualTo(GuestAccountMode.TEMPORARY);
    assertThat(link.isTemporaryAccount()).isTrue();
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.GUESTS_ONLY);

    link.applyGuestAccountMode(GuestAccountMode.GUEST_CHOOSES);
    assertThat(link.isTemporaryAccount()).isTrue();
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.BOTH);

    link.applyGuestAccountMode(GuestAccountMode.PERMANENT);
    assertThat(link.isTemporaryAccount()).isFalse();
    assertThat(link.getAccessMode()).isEqualTo(DirectInvitationAccessMode.ACCOUNTS_ONLY);
  }

  @Test
  void aRowWithoutAModeReadsAsTheMigrationWouldHaveSetIt() {
    var direct = AgencyInviteLink.builder().linkKind("COUNSELLOR").temporaryAccount(true).build();
    var call = AgencyInviteLink.builder().linkKind("CALL").temporaryAccount(true).build();
    var accountsOnlyCall = AgencyInviteLink.builder().linkKind("CALL").build();
    var guestsOnly =
        AgencyInviteLink.builder()
            .linkKind("COUNSELLOR")
            .temporaryAccount(true)
            .accessMode(DirectInvitationAccessMode.GUESTS_ONLY)
            .build();

    assertThat(direct.effectiveGuestAccountMode()).isEqualTo(GuestAccountMode.GUEST_CHOOSES);
    assertThat(call.effectiveGuestAccountMode()).isEqualTo(GuestAccountMode.TEMPORARY);
    assertThat(accountsOnlyCall.effectiveGuestAccountMode()).isEqualTo(GuestAccountMode.PERMANENT);
    assertThat(guestsOnly.effectiveGuestAccountMode()).isEqualTo(GuestAccountMode.TEMPORARY);
    assertThat(guestsOnly.effectiveAccessMode()).isEqualTo(DirectInvitationAccessMode.GUESTS_ONLY);
  }

  @Test
  void theModeDecidesWhichRegistrationsItAdmits() {
    assertThat(GuestAccountMode.TEMPORARY.admits(true)).isTrue();
    assertThat(GuestAccountMode.TEMPORARY.admits(false)).isFalse();
    assertThat(GuestAccountMode.PERMANENT.admits(true)).isFalse();
    assertThat(GuestAccountMode.PERMANENT.admits(false)).isTrue();
    assertThat(GuestAccountMode.GUEST_CHOOSES.admits(true)).isTrue();
    assertThat(GuestAccountMode.GUEST_CHOOSES.admits(false)).isTrue();
  }
}
