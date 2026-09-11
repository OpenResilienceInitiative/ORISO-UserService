package de.caritas.cob.userservice.api.workflow.notificationretention.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.notificationretention.service.EventNotificationRetentionService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventNotificationRetentionSchedulerTest {

  @InjectMocks private EventNotificationRetentionScheduler eventNotificationRetentionScheduler;

  @Mock private EventNotificationRetentionService eventNotificationRetentionService;

  @Mock private TenantContextProvider tenantContextProvider;

  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(eventNotificationRetentionScheduler, "claimDuration", Duration.ofHours(12));
  }

  @Test
  void purgeExpiredNotifications_purgesUnderTheTechnicalTenantContext() {
    when(taskClaimService.tryClaim("event-notification-retention", Duration.ofHours(12)))
        .thenReturn(true);

    eventNotificationRetentionScheduler.purgeExpiredNotifications();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(eventNotificationRetentionService).purgeExpiredNotifications();
  }

  /** Every replica runs the same cron, so exactly one of them may do the deleting. */
  @Test
  void purgeExpiredNotifications_skipsAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim("event-notification-retention", Duration.ofHours(12)))
        .thenReturn(false);

    eventNotificationRetentionScheduler.purgeExpiredNotifications();

    verifyNoInteractions(tenantContextProvider, eventNotificationRetentionService);
  }
}
