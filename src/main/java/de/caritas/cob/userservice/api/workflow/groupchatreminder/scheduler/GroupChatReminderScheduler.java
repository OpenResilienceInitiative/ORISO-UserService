package de.caritas.cob.userservice.api.workflow.groupchatreminder.scheduler;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.service.notification.GroupChatReminderService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically publishes the privacy-safe in-app reminder for upcoming group-chat occurrences. */
@Component
@RequiredArgsConstructor
public class GroupChatReminderScheduler {

  private final GroupChatReminderService reminderService;
  private final TenantContextProvider tenantContextProvider;

  @Value("${group.chat.reminder.enabled:true}")
  private Boolean enabled;

  @Scheduled(cron = "${group.chat.reminder.cron:0 */5 * * * ?}")
  public void publishUpcomingReminders() {
    if (!isTrue(enabled)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    try {
      reminderService.publishUpcomingReminders(nowInUtc());
    } finally {
      TenantContext.clear();
    }
  }
}
