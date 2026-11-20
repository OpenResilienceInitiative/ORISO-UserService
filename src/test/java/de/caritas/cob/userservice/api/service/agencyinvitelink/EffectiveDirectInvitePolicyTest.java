package de.caritas.cob.userservice.api.service.agencyinvitelink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class EffectiveDirectInvitePolicyTest {

  private static final long TENANT = 5L;
  private static final long AGENCY = 99L;

  @Mock private EffectiveAgencySettingsLookup agencySettings;
  @Mock private TenantService tenantService;
  private EffectiveDirectInvitePolicy policy;

  @BeforeEach
  void setUp() {
    policy = new EffectiveDirectInvitePolicy(agencySettings, tenantService);
    traegerAllows(true, true, true);
  }

  /** What the TenantService serves, read fresh, as the Träger's own switches. */
  private void traegerAllows(boolean chat, boolean audio, boolean video) {
    lenient()
        .when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDirectInviteChatEnabled(chat)
                        .featureDirectInviteAudioEnabled(audio)
                        .featureDirectInviteVideoEnabled(video)));
  }

  @ParameterizedTest
  @CsvSource({
    "CHAT, true, false, false, true",
    "CHAT, false, true, true, false",
    "AUDIO, false, true, false, true",
    "AUDIO, true, false, true, false",
    "VIDEO, false, false, true, true",
    "VIDEO, true, true, false, false"
  })
  void allows_Should_ReadTheSwitchOfTheAskedMediumOnly(
      InviteLinkChatType medium, boolean chat, boolean audio, boolean video, boolean expected) {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenReturn(
            Optional.of(
                new Settings()
                    .featureDirectInviteChatEnabled(chat)
                    .featureDirectInviteAudioEnabled(audio)
                    .featureDirectInviteVideoEnabled(video)));

    assertThat(policy.allows(TENANT, AGENCY, medium)).isEqualTo(expected);
  }

  @Test
  void allows_Should_AskInTheLinksTraeger_AndLeaveNoContextBehind() {
    var askedIn = new java.util.concurrent.atomic.AtomicReference<Long>();
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenAnswer(
            invocation -> {
              askedIn.set(TenantContext.getCurrentTenant());
              return Optional.of(new Settings().featureDirectInviteChatEnabled(true));
            });
    TenantContext.clear();

    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT)).isTrue();

    assertThat(askedIn.get()).isEqualTo(TENANT);
    assertThat(TenantContext.getCurrentTenantData()).isNull();
  }

  /** The AgencyService caches tenant data: its "yes" is not enough once the Träger said no. */
  @Test
  void allows_Should_Refuse_When_TheTraegerSwitchedOff_EvenIfTheAgencyServiceStillServesYes() {
    traegerAllows(true, true, false);
    lenient()
        .when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenReturn(Optional.of(new Settings().featureDirectInviteVideoEnabled(true)));

    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.VIDEO)).isFalse();
  }

  @Test
  void allows_Should_Refuse_When_TheTraegerValueIsUnknown() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT)).thenReturn(null);
    lenient()
        .when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenReturn(Optional.of(new Settings().featureDirectInviteChatEnabled(true)));
    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT)).isFalse();

    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(new RestrictedTenantDTO().id(TENANT));
    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT)).isFalse();
  }

  /** A 4xx that is not a timeout is the provider saying no to the question: deny, no outage. */
  @Test
  void allows_Should_Deny_NotReportAnOutage_When_TheAgencyServiceAnswers400() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT)).isFalse();
  }

  @Test
  void allows_Should_Deny_NotReportAnOutage_When_TheTenantServiceAnswers400() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT)).isFalse();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {408, 429})
  void allows_Should_ReportAnOutage_When_AProviderAsksToTryAgain(int status) {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenThrow(new HttpClientErrorException(HttpStatus.valueOf(status)));

    assertThatThrownBy(() -> policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void allows_Should_ReportAnOutage_When_AProviderAnswers5xx() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

    assertThatThrownBy(() -> policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void allows_Should_ReportAnOutage_When_TheTenantServiceIsDown() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenThrow(new ResourceAccessException("timeout"));

    assertThatThrownBy(() -> policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void allows_Should_RestoreTheCallersTenantContext_Also_WhenTheLookupThrows() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenThrow(new ResourceAccessException("timeout"));
    TenantContext.setCurrentTenant(1L);
    try {
      assertThatThrownBy(() -> policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT))
          .isInstanceOf(ServiceUnavailableException.class);
      assertThat(TenantContext.getCurrentTenant()).isEqualTo(1L);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void allows_Should_Refuse_When_TheSwitchIsNotServed() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenReturn(Optional.of(new Settings().featureDirectInviteChatEnabled(true)));

    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.VIDEO)).isFalse();
  }

  @Test
  void allows_Should_Refuse_When_TheAgencyHasNoSettings() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT)).thenReturn(Optional.empty());

    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT)).isFalse();
  }

  @Test
  void allows_Should_Refuse_When_TheAgencyServiceRefusesAccess() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenThrow(new ForbiddenException("another tenant"));

    assertThatThrownBy(() -> policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void allows_Should_ReportAnOutage_NotAnAllowance() {
    when(agencySettings.findEffectiveSettings(AGENCY, TENANT))
        .thenThrow(new ResourceAccessException("timeout"));

    assertThatThrownBy(() -> policy.allows(TENANT, AGENCY, InviteLinkChatType.CHAT))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void allows_Should_NeverAllowAMediumThatIsNoDirectMediumOrWithoutTenant() {
    assertThat(policy.allows(TENANT, AGENCY, InviteLinkChatType.LIVE_CHAT)).isFalse();
    assertThat(policy.allows(TENANT, AGENCY, null)).isFalse();
    assertThat(policy.allows(null, AGENCY, InviteLinkChatType.CHAT)).isFalse();
    verifyNoInteractions(agencySettings, tenantService);
  }

  @Test
  void allows_Should_UseTheTraegerSwitch_When_TheLinkHasNoAgency() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDirectInviteAudioEnabled(true)
                        .featureDirectInviteVideoEnabled(false)));

    assertThat(policy.allows(TENANT, null, InviteLinkChatType.AUDIO)).isTrue();
    assertThat(policy.allows(TENANT, null, InviteLinkChatType.VIDEO)).isFalse();
    assertThat(policy.allows(TENANT, null, InviteLinkChatType.CHAT)).isFalse();
    verifyNoInteractions(agencySettings);
  }

  @Test
  void allows_Should_Refuse_When_TheTenantAnswerIsForeignOrEmpty() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT + 1)
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureDirectInviteChatEnabled(true)));
    assertThat(policy.allows(TENANT, null, InviteLinkChatType.CHAT)).isFalse();

    when(tenantService.getRestrictedTenantDataFresh(TENANT)).thenReturn(null);
    assertThat(policy.allows(TENANT, null, InviteLinkChatType.CHAT)).isFalse();
  }
}
