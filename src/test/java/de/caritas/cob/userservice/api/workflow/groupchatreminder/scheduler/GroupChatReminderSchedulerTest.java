package de.caritas.cob.userservice.api.workflow.groupchatreminder.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import de.caritas.cob.userservice.api.service.notification.GroupChatReminderService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GroupChatReminderSchedulerTest {

  @InjectMocks private GroupChatReminderScheduler scheduler;
  @Mock private GroupChatReminderService reminderService;
  @Mock private TenantContextProvider tenantContextProvider;

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void publishUpcomingRemindersShouldClearTechnicalTenantContextAfterSuccess() {
    enableSchedulerAndSetTechnicalContext();

    scheduler.publishUpcomingReminders();

    assertFalse(TenantContext.contextIsSet());
  }

  @Test
  void publishUpcomingRemindersShouldClearTechnicalTenantContextAfterFailure() {
    enableSchedulerAndSetTechnicalContext();
    doThrow(new IllegalStateException("notification storage unavailable"))
        .when(reminderService)
        .publishUpcomingReminders(org.mockito.ArgumentMatchers.any());

    assertThrows(IllegalStateException.class, scheduler::publishUpcomingReminders);
    assertFalse(TenantContext.contextIsSet());
  }

  private void enableSchedulerAndSetTechnicalContext() {
    ReflectionTestUtils.setField(scheduler, "enabled", true);
    doAnswer(
            invocation -> {
              TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
              return null;
            })
        .when(tenantContextProvider)
        .setTechnicalContextIfMultiTenancyIsEnabled();
  }
}
