package de.caritas.cob.userservice.api.workflow.deactivate.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.deactivate.service.DeactivateAnonymousUserService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeactivateAnonymousUserSchedulerTest {

  @InjectMocks private DeactivateAnonymousUserScheduler deactivateAnonymousUserScheduler;

  @Mock private DeactivateAnonymousUserService deactivateAnonymousUserService;

  @Mock private TenantContextProvider tenantContextProvider;

  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(deactivateAnonymousUserScheduler, "claimDuration", Duration.ofMinutes(30));
  }

  @Test
  void performDeactivationWorkflow_Should_useService() {
    when(taskClaimService.tryClaim("anonymous-user-deactivation", Duration.ofMinutes(30)))
        .thenReturn(true);

    this.deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(this.deactivateAnonymousUserService).deactivateStaleAnonymousUsers();
  }

  @Test
  void performDeactivationWorkflowShouldSkipAllDownstreamCallsWhenClaimIsLost() {
    when(taskClaimService.tryClaim("anonymous-user-deactivation", Duration.ofMinutes(30)))
        .thenReturn(false);

    deactivateAnonymousUserScheduler.performDeactivationWorkflow();

    verifyNoInteractions(tenantContextProvider, deactivateAnonymousUserService);
  }
}
