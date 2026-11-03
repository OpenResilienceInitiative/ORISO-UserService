package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(ConfiguredDirectInvitePolicy.class)
class ConfiguredDirectInvitePolicyTest {

  @Autowired private DirectInvitePolicy policy;
  @MockitoBean private EffectiveAgencySettingsLookup agencySettings;
  @MockitoBean private TenantService tenants;

  @Test
  void readsTheEffectivePermissionForTheRequestedMediumOnEveryAttempt() {
    tenantVideoAllowed(true);
    when(agencySettings.findEffectiveSettings(42L, 7L))
        .thenReturn(
            Optional.of(
                new Settings()
                    .featureDirectInviteVideoEnabled(false)
                    .featureDirectInviteAudioEnabled(true)))
        .thenReturn(Optional.of(new Settings().featureDirectInviteVideoEnabled(true)));

    assertThat(policy.allows(7L, 42L, InviteLinkChatType.VIDEO)).isFalse();
    assertThat(policy.allows(7L, 42L, InviteLinkChatType.VIDEO)).isTrue();
  }

  @Test
  void deniesWhenEffectiveSettingsAreMissing() {
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDirectInviteAudioEnabled(true)));
    assertThat(policy.allows(7L, 42L, InviteLinkChatType.AUDIO)).isFalse();
  }

  @Test
  void deniesWhenTraegerDisablesTheMediumEvenIfAgencyProjectionAllowsIt() {
    tenantVideoAllowed(false);
    when(agencySettings.findEffectiveSettings(42L, 7L))
        .thenReturn(Optional.of(new Settings().featureDirectInviteVideoEnabled(true)));

    assertThat(policy.allows(7L, 42L, InviteLinkChatType.VIDEO)).isFalse();
  }

  @Test
  void usesTenantPolicyForCallsWithoutAnAgency() {
    tenantVideoAllowed(true);
    assertThat(policy.allows(7L, null, InviteLinkChatType.VIDEO)).isTrue();
    assertThat(policy.allows(7L, null, InviteLinkChatType.CHAT)).isFalse();
  }

  private void tenantVideoAllowed(boolean enabled) {
    when(tenants.getRestrictedTenantDataFresh(7L))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(7L)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDirectInviteVideoEnabled(enabled)));
  }
}
