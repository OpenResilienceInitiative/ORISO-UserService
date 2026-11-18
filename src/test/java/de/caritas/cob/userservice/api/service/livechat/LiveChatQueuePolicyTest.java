package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsDTO;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.FeatureToggleDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/** ORISO-Admin#1497: the platform switch opens the queue; anything else keeps it strict. */
@ExtendWith(MockitoExtension.class)
class LiveChatQueuePolicyTest {

  @Mock private ApplicationSettingsService applicationSettingsService;
  @InjectMocks private LiveChatQueuePolicy policy;

  @Test
  void freeChoiceEnabled_Should_BeTrue_When_PlatformSwitchIsOn() {
    when(applicationSettingsService.fetchApplicationSettings()).thenReturn(settings(true));

    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isTrue();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_PlatformSwitchIsOff() {
    when(applicationSettingsService.fetchApplicationSettings()).thenReturn(settings(false));

    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_SettingIsMissing() {
    when(applicationSettingsService.fetchApplicationSettings())
        .thenReturn(new ApplicationSettingsDTO());

    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_ValueIsMissing() {
    var settings = new ApplicationSettingsDTO();
    settings.setLiveChatFreeChoiceEnabled(new FeatureToggleDTO());
    when(applicationSettingsService.fetchApplicationSettings()).thenReturn(settings);

    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_SettingsAreEmpty() {
    when(applicationSettingsService.fetchApplicationSettings()).thenReturn(null);

    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_FallBackToStrict_When_SettingsBecomeUnreachable() {
    when(applicationSettingsService.fetchApplicationSettings())
        .thenReturn(settings(true))
        .thenThrow(new RestClientException("ConsultingTypeService down"));
    policy.refresh();

    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_StayStrictAndNotCallOut_When_NeverRefreshed() {
    assertThat(policy.freeChoiceEnabled()).isFalse();

    verifyNoInteractions(applicationSettingsService);
  }

  @Test
  void freeChoiceEnabled_Should_ReadTheSnapshotOnly_When_CalledAfterRefresh() {
    when(applicationSettingsService.fetchApplicationSettings())
        .thenReturn(settings(true))
        .thenThrow(new AssertionError("the guard must not call ConsultingTypeService"));
    policy.refresh();

    assertThat(policy.freeChoiceEnabled()).isTrue();
    assertThat(policy.freeChoiceEnabled()).isTrue();
  }

  private static ApplicationSettingsDTO settings(boolean enabled) {
    var settings = new ApplicationSettingsDTO();
    settings.setLiveChatFreeChoiceEnabled(new FeatureToggleDTO().value(enabled).readOnly(false));
    return settings;
  }
}
