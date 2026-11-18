package de.caritas.cob.userservice.api.service.livechat;

import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ORISO-Admin#1497: platform-wide choice between the strict live-chat queue (only the
 * longest-waiting guest may be accepted) and free choice (any waiting guest). Only an explicit
 * {@code true} opens the queue; a missing or unreadable setting keeps today's strict order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveChatQueuePolicy {

  private final @NonNull ApplicationSettingsService applicationSettingsService;

  public boolean freeChoiceEnabled() {
    try {
      var settings = applicationSettingsService.getApplicationSettings();
      if (settings == null || settings.getLiveChatFreeChoiceEnabled() == null) {
        return false;
      }
      return Boolean.TRUE.equals(settings.getLiveChatFreeChoiceEnabled().getValue());
    } catch (RuntimeException failure) {
      log.warn(
          "Live-chat queue setting unreadable, keeping the strict order ({})",
          failure.getClass().getSimpleName());
      return false;
    }
  }
}
