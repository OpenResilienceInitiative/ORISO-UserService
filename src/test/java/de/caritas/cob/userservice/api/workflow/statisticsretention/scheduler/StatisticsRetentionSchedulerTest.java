package de.caritas.cob.userservice.api.workflow.statisticsretention.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.statisticsretention.service.StatisticsRetentionService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatisticsRetentionSchedulerTest {

  @InjectMocks private StatisticsRetentionScheduler scheduler;

  @Mock private StatisticsRetentionService statisticsRetentionService;

  @Mock private TenantContextProvider tenantContextProvider;

  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(scheduler, "claimDuration", Duration.ofHours(12));
  }

  @Test
  void purgeExpiredStatistics_purgesUnderTheTechnicalTenantContext() {
    when(taskClaimService.tryClaim("statistics-retention", Duration.ofHours(12))).thenReturn(true);

    scheduler.purgeExpiredStatistics();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(statisticsRetentionService).purgeExpiredStatistics();
  }

  /** Every replica runs the same cron, so exactly one of them may do the deleting. */
  @Test
  void purgeExpiredStatistics_skipsAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim("statistics-retention", Duration.ofHours(12))).thenReturn(false);

    scheduler.purgeExpiredStatistics();

    verifyNoInteractions(tenantContextProvider, statisticsRetentionService);
  }
}
