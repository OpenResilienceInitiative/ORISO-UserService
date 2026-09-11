package de.caritas.cob.userservice.api.workflow.notificationretention.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.notificationretention.service.EventNotificationRetentionService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for the event-notification retention purge (KDG epic #1010, task 2a). */
@Component
@RequiredArgsConstructor
public class EventNotificationRetentionScheduler {

  private static final String TASK_NAME = "event-notification-retention";

  private final @NonNull EventNotificationRetentionService eventNotificationRetentionService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${event.notification.retention.claim.duration:PT12H}")
  private Duration claimDuration;

  /** Entry method to purge notifications that have outlived their retention period. */
  @Scheduled(cron = "${event.notification.retention.cron:0 15 3 * * ?}")
  public void purgeExpiredNotifications() {
    if (!taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    this.eventNotificationRetentionService.purgeExpiredNotifications();
  }
}
