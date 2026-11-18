package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DeletedUnitInviteRetrySchedulerTest {

  @Mock private DeletedUnitInviteRevocation revocation;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private ScheduledTaskClaimService taskClaimService;
  @InjectMocks private DeletedUnitInviteRetryScheduler scheduler;

  @BeforeEach
  void claimDuration() {
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofMinutes(10));
  }

  @Test
  void retries_WhenThisReplicaHoldsTheClaim() {
    when(taskClaimService.tryClaim(eq(DeletedUnitInviteRetryScheduler.TASK_NAME), any()))
        .thenReturn(true);

    scheduler.retryUnfinishedRevocations();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(revocation).retryUnfinished();
  }

  @Test
  void doesNothing_WhenAnotherReplicaHoldsTheClaim() {
    when(taskClaimService.tryClaim(eq(DeletedUnitInviteRetryScheduler.TASK_NAME), any()))
        .thenReturn(false);

    scheduler.retryUnfinishedRevocations();

    verifyNoInteractions(revocation, tenantContextProvider);
  }

  @Test
  void aFailedRun_Should_NotEscapeTheScheduler() {
    when(taskClaimService.tryClaim(eq(DeletedUnitInviteRetryScheduler.TASK_NAME), any()))
        .thenReturn(true);
    when(revocation.retryUnfinished()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduler::retryUnfinishedRevocations).doesNotThrowAnyException();
  }
}
