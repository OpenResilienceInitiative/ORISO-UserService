package de.caritas.cob.userservice.api.service.livechat;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@link LiveChatQueuePolicy} snapshot current outside the acceptance transaction
 * (ORISO-Admin#1497). Every replica refreshes its own snapshot; the first run is at startup.
 */
@Component
@Profile("!testing")
@RequiredArgsConstructor
public class LiveChatQueuePolicyRefresher {

  private final @NonNull LiveChatQueuePolicy liveChatQueuePolicy;

  @Scheduled(initialDelay = 0, fixedDelayString = "${live.chat.queue.policyRefreshMillis:30000}")
  public void refresh() {
    liveChatQueuePolicy.refresh();
  }
}
