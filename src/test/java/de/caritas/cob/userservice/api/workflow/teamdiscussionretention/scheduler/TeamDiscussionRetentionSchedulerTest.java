package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.scheduler;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service.TeamDiscussionRetentionService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamDiscussionRetentionSchedulerTest {

  @InjectMocks private TeamDiscussionRetentionScheduler underTest;

  @Mock private TeamDiscussionRetentionService retentionService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(underTest, "claimDuration", Duration.ofHours(12));
    when(retentionService.isEnabled()).thenReturn(true);
  }

  @Test
  void purgeExpiredDiscussions_purgesUnderTheTechnicalTenantContext() {
    when(taskClaimService.tryClaim(
            TeamDiscussionRetentionScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(true);

    underTest.purgeExpiredDiscussions();

    var inOrder = inOrder(tenantContextProvider, retentionService);
    inOrder.verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    inOrder.verify(retentionService).purgeExpiredDiscussions();
  }

  /** The technical context must not leak into the next task on the pooled scheduler thread. */
  @Test
  void purgeExpiredDiscussions_clearsTheTenantContextAfterwards_evenWhenThePurgeThrows() {
    when(taskClaimService.tryClaim(
            TeamDiscussionRetentionScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(true);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
        .when(retentionService)
        .purgeExpiredDiscussions();

    org.assertj.core.api.Assertions.assertThatThrownBy(underTest::purgeExpiredDiscussions)
        .isInstanceOf(IllegalStateException.class);

    org.assertj.core.api.Assertions.assertThat(TenantContext.contextIsSet()).isFalse();
  }

  /** Every replica runs the same cron, so exactly one of them may do the deleting. */
  @Test
  void purgeExpiredDiscussions_skipsAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim(
            TeamDiscussionRetentionScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(false);

    underTest.purgeExpiredDiscussions();

    verifyNoInteractions(tenantContextProvider);
    verify(retentionService).isEnabled();
    verifyNoMoreInteractions(retentionService);
  }

  /** Even an early return must not leave a context on the pooled thread. */
  @Test
  void purgeExpiredDiscussions_clearsTheTenantContext_When_theClaimIsLost() {
    when(taskClaimService.tryClaim(
            TeamDiscussionRetentionScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(false);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);

    underTest.purgeExpiredDiscussions();

    org.assertj.core.api.Assertions.assertThat(TenantContext.contextIsSet()).isFalse();
  }

  /** A disabled job must not even take the claim, so nothing is logged or leased for nothing. */
  @Test
  void purgeExpiredDiscussions_doesNotClaim_When_theJobIsDisabled() {
    when(retentionService.isEnabled()).thenReturn(false);

    underTest.purgeExpiredDiscussions();

    verifyNoInteractions(taskClaimService, tenantContextProvider);
    verify(retentionService).isEnabled();
    verifyNoMoreInteractions(retentionService);
  }
}
