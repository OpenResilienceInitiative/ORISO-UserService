package de.caritas.cob.userservice.api.service.livechat;

import static org.assertj.core.api.Assertions.assertThat;
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
    when(applicationSettingsService.getApplicationSettings()).thenReturn(settings(true));

    assertThat(policy.freeChoiceEnabled()).isTrue();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_PlatformSwitchIsOff() {
    when(applicationSettingsService.getApplicationSettings()).thenReturn(settings(false));

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_SettingIsMissing() {
    when(applicationSettingsService.getApplicationSettings())
        .thenReturn(new ApplicationSettingsDTO());

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_ValueIsMissing() {
    var settings = new ApplicationSettingsDTO();
    settings.setLiveChatFreeChoiceEnabled(new FeatureToggleDTO());
    when(applicationSettingsService.getApplicationSettings()).thenReturn(settings);

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_SettingsAreEmpty() {
    when(applicationSettingsService.getApplicationSettings()).thenReturn(null);

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  @Test
  void freeChoiceEnabled_Should_BeFalse_When_SettingsAreUnreachable() {
    when(applicationSettingsService.getApplicationSettings())
        .thenThrow(new RestClientException("ConsultingTypeService down"));

    assertThat(policy.freeChoiceEnabled()).isFalse();
  }

  private static ApplicationSettingsDTO settings(boolean enabled) {
    var settings = new ApplicationSettingsDTO();
    settings.setLiveChatFreeChoiceEnabled(new FeatureToggleDTO().value(enabled).readOnly(false));
    return settings;
  }
}
