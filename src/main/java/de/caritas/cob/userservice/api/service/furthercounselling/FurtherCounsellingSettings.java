package de.caritas.cob.userservice.api.service.furthercounselling;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Reads the FE#1876 platform settings; a missing or unreadable value means the default. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FurtherCounsellingSettings {

  private final @NonNull ApplicationSettingsService applicationSettingsService;

  public TopicDuplicateMode topicDuplicateMode() {
    try {
      var settings = applicationSettingsService.getApplicationSettings();
      if (settings == null || settings.getTopicDuplicateMode() == null) {
        return TopicDuplicateMode.DEFAULT;
      }
      return TopicDuplicateMode.parse(settings.getTopicDuplicateMode().getValue());
    } catch (RuntimeException failure) {
      log.warn(
          "Topic duplicate mode unreadable, using default ({})",
          failure.getClass().getSimpleName());
      return TopicDuplicateMode.DEFAULT;
    }
  }
}
