package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.scheduler;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service.InactiveAccountNotificationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler for Security-06 12-month inactivity notifications. */
@Component
@RequiredArgsConstructor
public class InactiveAccountNotificationScheduler {

  private final @NonNull InactiveAccountNotificationService inactiveAccountNotificationService;
  private final @NonNull TenantContextProvider tenantContextProvider;

  @Value("${inactive.account.notification.enabled:false}")
  private boolean inactiveAccountNotificationEnabled;

  @Scheduled(cron = "${inactive.account.notification.cron:0 30 2 * * ?}")
  public void notifyInactiveAccounts() {
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    if (inactiveAccountNotificationEnabled) {
      inactiveAccountNotificationService.scanAndNotifyInactiveAccounts();
    }
  }
}
