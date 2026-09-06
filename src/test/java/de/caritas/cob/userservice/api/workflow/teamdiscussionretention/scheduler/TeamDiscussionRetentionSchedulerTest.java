package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

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
    when(taskClaimService.tryClaim("team-discussion-archive-retention", Duration.ofHours(12)))
        .thenReturn(true);

    underTest.purgeExpiredDiscussions();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(retentionService).purgeExpiredDiscussions();
  }

  /** Every replica runs the same cron, so exactly one of them may do the deleting. */
  @Test
  void purgeExpiredDiscussions_skipsAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim("team-discussion-archive-retention", Duration.ofHours(12)))
        .thenReturn(false);

    underTest.purgeExpiredDiscussions();

    verifyNoInteractions(tenantContextProvider);
    verify(retentionService).isEnabled();
    verifyNoMoreInteractions(retentionService);
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
