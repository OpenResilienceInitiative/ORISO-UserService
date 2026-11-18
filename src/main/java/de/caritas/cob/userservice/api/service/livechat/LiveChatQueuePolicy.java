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
 *
 * <p>The queue guard runs while the acceptance transaction holds the session row lock, so it must
 * never wait on ConsultingTypeService. {@link #freeChoiceEnabled()} therefore only reads a local
 * snapshot; {@link #refresh()} reads the setting and is called outside any acceptance transaction
 * by {@link LiveChatQueuePolicyRefresher}. Until the first refresh succeeds the queue stays strict.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveChatQueuePolicy {

  private final @NonNull ApplicationSettingsService applicationSettingsService;

  private volatile boolean freeChoiceEnabled;

  /** The last snapshot of the platform switch. Never calls out, so it is safe under a row lock. */
  public boolean freeChoiceEnabled() {
    return freeChoiceEnabled;
  }

  /**
   * Re-reads the platform switch, bypassing the application-settings cache so an Admin change is
   * picked up on the next refresh. A failed read falls back to the strict order.
   */
  public void refresh() {
    freeChoiceEnabled = readFreeChoiceEnabled();
  }

  private boolean readFreeChoiceEnabled() {
    try {
      var settings = applicationSettingsService.fetchApplicationSettings();
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
