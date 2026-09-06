package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

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
    when(taskClaimService.tryClaim("team-discussion-orphan-cleanup", Duration.ofHours(12)))
        .thenReturn(true);

    scheduler.purgeOrphanedDiscussions();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(teamDiscussionOrphanCleanupService).purgeOrphanedDiscussions();
  }

  @Test
  void purgeOrphanedDiscussions_skipsAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim("team-discussion-orphan-cleanup", Duration.ofHours(12)))
        .thenReturn(false);

    scheduler.purgeOrphanedDiscussions();

    verifyNoInteractions(tenantContextProvider, teamDiscussionOrphanCleanupService);
  }
}
