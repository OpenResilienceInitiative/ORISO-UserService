package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static de.caritas.cob.userservice.api.model.GuestAccountMode.GUEST_CHOOSES;
import static de.caritas.cob.userservice.api.model.GuestAccountMode.PERMANENT;
import static de.caritas.cob.userservice.api.model.GuestAccountMode.TEMPORARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

/** #1662: the guest account modes a counsellor may offer are the platform's AND the Träger's. */
@ExtendWith(MockitoExtension.class)
class TraegerGuestAccountModePolicyTest {

  private static final long TENANT = 5L;
  private static final long AGENCY = 99L;

  @Mock private TenantService tenantService;

  private TraegerGuestAccountModePolicy policy(boolean platformAllowsTemporary) {
    return new TraegerGuestAccountModePolicy(
        new PlatformGuestAccountModePolicy((tenant, agency, medium) -> platformAllowsTemporary),
        tenantService);
  }

  private void traegerServes(Settings settings) {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(new RestrictedTenantDTO().id(TENANT).settings(settings));
  }

  @Test
  void everyModeWhileNeitherPlatformNorTraegerRestrictsIt() {
    traegerServes(
        new Settings()
            .featureGuestAccountTemporaryVideoEnabled(true)
            .featureGuestAccountGuestChoosesVideoEnabled(true)
            .featureGuestAccountPermanentVideoEnabled(true));

    assertThat(policy(true).allowedModes(TENANT, AGENCY, InviteLinkChatType.VIDEO))
        .containsExactlyInAnyOrder(TEMPORARY, GUEST_CHOOSES, PERMANENT);
  }

  @Test
  void theTraegerRemovesTheModesItSwitchedOffForTheAskedMediumOnly() {
    traegerServes(
        new Settings()
            .featureGuestAccountTemporaryChatEnabled(false)
            .featureGuestAccountGuestChoosesChatEnabled(true)
            .featureGuestAccountPermanentChatEnabled(true)
            .featureGuestAccountTemporaryAudioEnabled(true)
            .featureGuestAccountGuestChoosesAudioEnabled(false)
            .featureGuestAccountPermanentAudioEnabled(true)
            .featureGuestAccountTemporaryVideoEnabled(true)
            .featureGuestAccountGuestChoosesVideoEnabled(true)
            .featureGuestAccountPermanentVideoEnabled(false));

    var policy = policy(true);
    assertThat(policy.allowedModes(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .containsExactlyInAnyOrder(GUEST_CHOOSES, PERMANENT);
    assertThat(policy.allowedModes(TENANT, AGENCY, InviteLinkChatType.AUDIO))
        .containsExactlyInAnyOrder(TEMPORARY, PERMANENT);
    assertThat(policy.allowedModes(TENANT, AGENCY, InviteLinkChatType.VIDEO))
        .containsExactlyInAnyOrder(TEMPORARY, GUEST_CHOOSES);
    assertThat(policy.cap(TENANT, AGENCY, InviteLinkChatType.VIDEO, PERMANENT))
        .as("a link set before the Träger dropped permanent accounts")
        .isEqualTo(GUEST_CHOOSES);
  }

  @Test
  void theTraegerCannotReopenWhatThePlatformForbids() {
    traegerServes(
        new Settings()
            .featureGuestAccountTemporaryChatEnabled(true)
            .featureGuestAccountGuestChoosesChatEnabled(true)
            .featureGuestAccountPermanentChatEnabled(true));

    assertThat(policy(false).allowedModes(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .containsExactly(PERMANENT);
  }

  @Test
  void aTenantServiceThatDoesNotServeTheFieldsYetRestrictsNothing() {
    traegerServes(new Settings().featureDirectInviteChatEnabled(true));

    assertThat(policy(true).allowedModes(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .containsExactlyInAnyOrder(TEMPORARY, GUEST_CHOOSES, PERMANENT);
  }

  @Test
  void aTraegerThatSwitchedOffEveryModeGetsPermanentAccountsOnly() {
    traegerServes(
        new Settings()
            .featureGuestAccountTemporaryChatEnabled(false)
            .featureGuestAccountGuestChoosesChatEnabled(false)
            .featureGuestAccountPermanentChatEnabled(false));

    assertThat(policy(true).allowedModes(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .containsExactly(PERMANENT);
  }

  @Test
  void anUnreadableTraegerOffersOnlyPermanentAccounts() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(new RestrictedTenantDTO().id(7L).settings(new Settings()));

    assertThat(policy(true).allowedModes(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .containsExactly(PERMANENT);
  }

  @Test
  void anUnreachableTenantServiceIsAnOutage() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenThrow(new ResourceAccessException("timeout"));

    assertThatThrownBy(() -> policy(true).allowedModes(TENANT, AGENCY, InviteLinkChatType.AUDIO))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void thePlatformAloneDecidesWhereNoTraegerIsKnownOrTheMediumHasNoSwitch() {
    var policy = policy(true);
    assertThat(policy.allowedModes(null, AGENCY, InviteLinkChatType.CHAT))
        .containsExactlyInAnyOrder(TEMPORARY, GUEST_CHOOSES, PERMANENT);
    assertThat(policy.allowedModes(TENANT, AGENCY, InviteLinkChatType.LIVE_CHAT))
        .containsExactlyInAnyOrder(TEMPORARY, GUEST_CHOOSES, PERMANENT);
    verifyNoInteractions(tenantService);
  }
}
