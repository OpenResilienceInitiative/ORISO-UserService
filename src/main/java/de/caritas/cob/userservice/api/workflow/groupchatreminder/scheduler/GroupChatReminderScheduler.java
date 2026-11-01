package de.caritas.cob.userservice.api.workflow.groupchatreminder.scheduler;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.service.notification.GroupChatReminderService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically publishes the privacy-safe in-app reminder for upcoming group-chat occurrences. */
@Component
@RequiredArgsConstructor
public class GroupChatReminderScheduler {

  public static final String TASK_NAME = "group-chat-reminder";

  private final GroupChatReminderService reminderService;
  private final TenantContextProvider tenantContextProvider;
  private final ScheduledTaskClaimService claims;

  @Value("${group.chat.reminder.claim.duration:PT4M}")
  private Duration claimDuration;

  @Value("${group.chat.reminder.enabled:true}")
  private Boolean enabled;

  @Scheduled(cron = "${group.chat.reminder.cron:0 */5 * * * ?}")
  public void publishUpcomingReminders() {
    if (!isTrue(enabled)) {
      return;
    }
    // The technical tenant context is set before the lease transaction opens, so the session that
    // reads the chat series is created under it. The per-recipient deduplication key stays the
    // authoritative guard against duplicates; the claim stops two overlapping instances from both
    // scanning and publishing in the first place.
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    try {
      claims.runIfClaimed(
          TASK_NAME, claimDuration, () -> reminderService.publishUpcomingReminders(nowInUtc()));
    } finally {
      TenantContext.clear();
    }
  }
}
