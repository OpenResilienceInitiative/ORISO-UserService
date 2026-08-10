package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAnonymousService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeleteUserAnonymousSchedulerReplicaTest {

  @Test
  void twoSchedulerInstancesTriggerOneDeletionWorkflow() throws Exception {
    var deletionService = mock(DeleteUserAnonymousService.class);
    var tenantContextProvider = mock(TenantContextProvider.class);
    var taskClaimService = mock(ScheduledTaskClaimService.class);
    org.mockito.Mockito.when(
            taskClaimService.tryClaim("anonymous-user-deletion", Duration.ofMinutes(30)))
        .thenReturn(true, false);
    var first = newScheduler(deletionService, tenantContextProvider, taskClaimService);
    var second = newScheduler(deletionService, tenantContextProvider, taskClaimService);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult = executor.submit(() -> run(first, ready, start));
      var secondResult = executor.submit(() -> run(second, ready, start));

      if (!ready.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for scheduler instances");
      }
      start.countDown();
      firstResult.get(5, TimeUnit.SECONDS);
      secondResult.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    verify(tenantContextProvider, times(1)).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(deletionService, times(1)).deleteInactiveAnonymousUsers();
  }

  private DeleteUserAnonymousScheduler newScheduler(
      DeleteUserAnonymousService deletionService,
      TenantContextProvider tenantContextProvider,
      ScheduledTaskClaimService taskClaimService) {
    var scheduler =
        new DeleteUserAnonymousScheduler(deletionService, tenantContextProvider, taskClaimService);
    org.springframework.test.util.ReflectionTestUtils.setField(
        scheduler, "claimDuration", Duration.ofMinutes(30));
    return scheduler;
  }

  private void run(
      DeleteUserAnonymousScheduler scheduler, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    scheduler.performDeletionWorkflow();
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent replica proof");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
