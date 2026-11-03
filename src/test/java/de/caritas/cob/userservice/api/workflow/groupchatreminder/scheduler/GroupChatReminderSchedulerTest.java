package de.caritas.cob.userservice.api.workflow.groupchatreminder.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.service.notification.GroupChatReminderService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.InMemoryScheduledTaskClaimWriter;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GroupChatReminderSchedulerTest {

  @org.mockito.Spy
  private ScheduledTaskClaimService claims = InMemoryScheduledTaskClaimWriter.newClaimService();

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
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofMinutes(4));
    doAnswer(
            invocation -> {
              TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
              return null;
            })
        .when(tenantContextProvider)
        .setTechnicalContextIfMultiTenancyIsEnabled();
  }

  @Test
  void publishUpcomingRemindersShouldSkipWhenAnotherReplicaHoldsTheClaim() {
    ReflectionTestUtils.setField(scheduler, "enabled", true);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofMinutes(4));
    assertTrue(claims.tryClaim(GroupChatReminderScheduler.TASK_NAME, Duration.ofMinutes(4)));

    scheduler.publishUpcomingReminders();

    verifyNoInteractions(reminderService);
  }

  @Test
  void publishUpcomingRemindersShouldPublishOnceWhenTwoReplicasOverlap() throws Exception {
    enableSchedulerAndSetTechnicalContext();
    var firstReplicaPublishing = new CountDownLatch(1);
    var releaseFirstReplica = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstReplicaPublishing.countDown();
              assertTrue(releaseFirstReplica.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(reminderService)
        .publishUpcomingReminders(org.mockito.ArgumentMatchers.any());
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(scheduler::publishUpcomingReminders);
      assertTrue(firstReplicaPublishing.await(5, TimeUnit.SECONDS));

      scheduler.publishUpcomingReminders();

      releaseFirstReplica.countDown();
      first.get(5, TimeUnit.SECONDS);
    } finally {
      releaseFirstReplica.countDown();
      executor.shutdownNow();
    }

    verify(reminderService, times(1)).publishUpcomingReminders(org.mockito.ArgumentMatchers.any());
    assertTrue(
        claims.tryClaim(GroupChatReminderScheduler.TASK_NAME, Duration.ofMinutes(4)),
        "the claim is released after the run so the next tick can publish");
  }
}
