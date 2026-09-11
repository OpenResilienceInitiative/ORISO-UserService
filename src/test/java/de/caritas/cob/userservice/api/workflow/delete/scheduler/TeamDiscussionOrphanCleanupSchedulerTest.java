package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.TeamDiscussionOrphanCleanupService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamDiscussionOrphanCleanupSchedulerTest {

  @InjectMocks private TeamDiscussionOrphanCleanupScheduler scheduler;

  @Mock private TeamDiscussionOrphanCleanupService teamDiscussionOrphanCleanupService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(scheduler, "claimDuration", Duration.ofHours(12));
  }

  @Test
  void purgeOrphanedDiscussions_runsUnderTheTechnicalTenantContext() {
    when(taskClaimService.tryClaim(
            TeamDiscussionOrphanCleanupScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(true);

    scheduler.purgeOrphanedDiscussions();

    var order = inOrder(tenantContextProvider, teamDiscussionOrphanCleanupService);
    order.verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    order.verify(teamDiscussionOrphanCleanupService).purgeOrphanedDiscussions();
  }

  /** The technical context must not leak into the next task on the pooled scheduler thread. */
  @Test
  void purgeOrphanedDiscussions_clearsTheTenantContext_evenWhenTheCleanupThrows() {
    when(taskClaimService.tryClaim(
            TeamDiscussionOrphanCleanupScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(true);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    doThrow(new IllegalStateException("boom"))
        .when(teamDiscussionOrphanCleanupService)
        .purgeOrphanedDiscussions();

    assertThatThrownBy(scheduler::purgeOrphanedDiscussions)
        .isInstanceOf(IllegalStateException.class);

    assertThat(TenantContext.contextIsSet()).isFalse();
  }

  /** Even an early return must not leave a context on the pooled thread. */
  @Test
  void purgeOrphanedDiscussions_clearsTheTenantContext_When_theClaimIsLost() {
    when(taskClaimService.tryClaim(
            TeamDiscussionOrphanCleanupScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(false);
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);

    scheduler.purgeOrphanedDiscussions();

    assertThat(TenantContext.contextIsSet()).isFalse();
  }

  @Test
  void purgeOrphanedDiscussions_skipsAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim(
            TeamDiscussionOrphanCleanupScheduler.TASK_NAME, Duration.ofHours(12)))
        .thenReturn(false);

    scheduler.purgeOrphanedDiscussions();

    verifyNoInteractions(tenantContextProvider, teamDiscussionOrphanCleanupService);
  }
}
