package de.caritas.cob.userservice.api.workflow.deactivate.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.deactivate.service.DeactivateAnonymousUserService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DeactivateAnonymousUserSchedulerReplicaIT {

  private static final String TASK_NAME = "anonymous-user-deactivation";

  @Autowired private ScheduledTaskClaimRepository claimRepository;
  @Autowired private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  @AfterEach
  void deleteReplicaProofClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @Test
  void twoSchedulerInstancesTriggerOneDeactivationWorkflow() throws Exception {
    var deactivationService = mock(DeactivateAnonymousUserService.class);
    var tenantContextProvider = mock(TenantContextProvider.class);
    var first = newScheduler(deactivationService, tenantContextProvider);
    var second = newScheduler(deactivationService, tenantContextProvider);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var firstResult = executor.submit(() -> run(first, ready, start));
      var secondResult = executor.submit(() -> run(second, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      firstResult.get(5, TimeUnit.SECONDS);
      secondResult.get(5, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    verify(tenantContextProvider, times(1)).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(deactivationService, times(1)).deactivateStaleAnonymousUsers();
  }

  private DeactivateAnonymousUserScheduler newScheduler(
      DeactivateAnonymousUserService deactivationService,
      TenantContextProvider tenantContextProvider) {
    var scheduler =
        new DeactivateAnonymousUserScheduler(
            deactivationService, tenantContextProvider, taskClaimService);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofMinutes(30));
    return scheduler;
  }

  private void run(
      DeactivateAnonymousUserScheduler scheduler, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    scheduler.performDeactivationWorkflow();
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
