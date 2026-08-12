package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
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
class DeleteUserAccountSchedulerReplicaIT {

  private static final String TASK_NAME = "account-deletion";

  @Autowired private ScheduledTaskClaimRepository claimRepository;
  @Autowired private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  @AfterEach
  void deleteReplicaProofClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  @Test
  void twoSchedulerInstancesTriggerOneAccountDeletionWorkflow() throws Exception {
    var deletionService = mock(DeleteUserAccountService.class);
    var tenantContextProvider = mock(TenantContextProvider.class);
    var first = newScheduler(deletionService, tenantContextProvider);
    var second = newScheduler(deletionService, tenantContextProvider);
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
    verify(deletionService, times(1)).deleteUserAccounts();
  }

  private DeleteUserAccountScheduler newScheduler(
      DeleteUserAccountService deletionService, TenantContextProvider tenantContextProvider) {
    var scheduler =
        new DeleteUserAccountScheduler(deletionService, tenantContextProvider, taskClaimService);
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofHours(12));
    return scheduler;
  }

  private void run(
      DeleteUserAccountScheduler scheduler, CountDownLatch ready, CountDownLatch start) {
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
