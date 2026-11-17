package de.caritas.cob.userservice.api.workflow.inviteretention.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.inviteretention.service.InviteRetentionService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InviteRetentionSchedulerTest {

  @Mock private InviteRetentionService inviteRetentionService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private ScheduledTaskClaimService taskClaimService;
  @InjectMocks private InviteRetentionScheduler scheduler;

  @Test
  void purgeSettledInvites_Should_Purge_WhenThisReplicaHoldsTheClaim() {
    ReflectionTestUtils.setField(scheduler, "claimDuration", Duration.ofHours(12));
    when(taskClaimService.tryClaim(eq(InviteRetentionScheduler.TASK_NAME), any())).thenReturn(true);

    scheduler.purgeSettledInvites();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(inviteRetentionService).purge();
  }

  @Test
  void purgeSettledInvites_Should_DoNothing_WhenAnotherReplicaHoldsTheClaim() {
    when(taskClaimService.tryClaim(eq(InviteRetentionScheduler.TASK_NAME), any()))
        .thenReturn(false);

    scheduler.purgeSettledInvites();

    verifyNoInteractions(inviteRetentionService, tenantContextProvider);
  }
}
