package de.caritas.cob.userservice.api.workflow.callobservationretention.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.callobservationretention.service.CallObservationRetentionService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallObservationRetentionSchedulerTest {
  private final CallObservationRetentionService service =
      mock(CallObservationRetentionService.class);
  private final TenantContextProvider tenants = mock(TenantContextProvider.class);
  private final ScheduledTaskClaimService claims = mock(ScheduledTaskClaimService.class);
  private final CallObservationRetentionScheduler scheduler =
      new CallObservationRetentionScheduler(service, tenants, claims);

  @BeforeEach
  void setUp() {
    setField(scheduler, "claimDuration", Duration.ofHours(12));
  }

  @Test
  void purgesUnderTheTechnicalTenantContextWhenItHoldsTheClaim() {
    when(claims.tryClaim("matrix-call-observation-retention", Duration.ofHours(12)))
        .thenReturn(true);

    scheduler.purgeExpiredObservations();

    verify(tenants).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(service).purgeExpired();
  }

  @Test
  void doesNothingWhenAnotherInstanceHoldsTheClaim() {
    when(claims.tryClaim("matrix-call-observation-retention", Duration.ofHours(12)))
        .thenReturn(false);

    scheduler.purgeExpiredObservations();

    verifyNoInteractions(service, tenants);
  }
}
