package de.caritas.cob.userservice.api.service.furthercounselling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.ApplicationSettingsDTO;
import de.caritas.cob.userservice.applicationsettingsservice.generated.web.model.SettingDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopicDuplicateModeTest {

  @Mock ApplicationSettingsService applicationSettingsService;
  @InjectMocks FurtherCounsellingSettings settings;

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "SOMETHING_NEW", "inform_existing_counsellor_once"})
  void absentOrUnknownValuesFallBackToTheDefault(String value) {
    assertThat(TopicDuplicateMode.parse(value))
        .isEqualTo(TopicDuplicateMode.INFORM_EXISTING_COUNSELLOR_ONCE);
  }

  @Test
  void knownValuesAreRead() {
    assertThat(TopicDuplicateMode.parse(" FORBID ")).isEqualTo(TopicDuplicateMode.FORBID);
  }

  @Test
  void missingSettingMeansDefault() {
    when(applicationSettingsService.getApplicationSettings())
        .thenReturn(new ApplicationSettingsDTO());

    assertThat(settings.topicDuplicateMode()).isEqualTo(TopicDuplicateMode.DEFAULT);
  }

  @Test
  void unreadableSettingsMeanDefault() {
    when(applicationSettingsService.getApplicationSettings())
        .thenThrow(new IllegalStateException("down"));

    assertThat(settings.topicDuplicateMode()).isEqualTo(TopicDuplicateMode.DEFAULT);
  }

  @Test
  void storedSettingIsUsed() {
    var dto = new ApplicationSettingsDTO();
    dto.setTopicDuplicateMode(new SettingDTO().value("INFORM_BOTH_ONCE").readOnly(false));
    when(applicationSettingsService.getApplicationSettings()).thenReturn(dto);

    assertThat(settings.topicDuplicateMode()).isEqualTo(TopicDuplicateMode.INFORM_BOTH_ONCE);
  }
}
