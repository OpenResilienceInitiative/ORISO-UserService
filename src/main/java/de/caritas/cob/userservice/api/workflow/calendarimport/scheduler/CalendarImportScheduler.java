package de.caritas.cob.userservice.api.workflow.calendarimport.scheduler;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.service.calendar.CalendarSyncService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the busy times imported from the counsellors' own calendars (ADR-032,
 * ORISO-UserService#1645). One replica per run: the others find the claim taken.
 */
@Component
@RequiredArgsConstructor
public class CalendarImportScheduler {

  private static final String TASK_NAME = "calendar-busy-import";

  private final @NonNull CalendarSyncService calendarSyncService;
  private final @NonNull TenantContextProvider tenantContextProvider;
  private final @NonNull ScheduledTaskClaimService taskClaimService;

  @Value("${calendar.import.enabled:true}")
  private Boolean enabled;

  @Value("${calendar.import.claim.duration:PT10M}")
  private Duration claimDuration;

  @Scheduled(cron = "${calendar.import.cron:0 */15 * * * ?}")
  public void importBusyTimes() {
    if (!isTrue(enabled) || !taskClaimService.tryClaim(TASK_NAME, claimDuration)) {
      return;
    }
    tenantContextProvider.setTechnicalContextIfMultiTenancyIsEnabled();
    try {
      calendarSyncService.importAll();
    } finally {
      TenantContext.clear();
    }
  }
}
