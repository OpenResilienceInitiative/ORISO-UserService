package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteTemporaryAccountsService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The technical context must not leak into the next task on the pooled scheduler thread. */
@ExtendWith(MockitoExtension.class)
class DeleteTemporaryAccountsSchedulerTest {

  @InjectMocks private DeleteTemporaryAccountsScheduler scheduler;

  @Mock private DeleteTemporaryAccountsService deleteTemporaryAccountsService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(scheduler, "enabled", true);
    setField(scheduler, "claimDuration", Duration.ofMinutes(30));
    when(taskClaimService.tryClaim(anyString(), any())).thenReturn(true);
    doAnswer(
            invocation -> {
              TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
              return null;
            })
        .when(tenantContextProvider)
        .setTechnicalContextIfMultiTenancyIsEnabled();
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void performDeletionWorkflow_clearsTheTenantContext_afterARun() {
    scheduler.performDeletionWorkflow();

    assertThat(TenantContext.contextIsSet()).isFalse();
  }

  @Test
  void performDeletionWorkflow_clearsTheTenantContext_evenWhenTheDeletionThrows() {
    doThrow(new IllegalStateException("boom"))
        .when(deleteTemporaryAccountsService)
        .deleteExpiredTemporaryAccounts();

    assertThatThrownBy(scheduler::performDeletionWorkflow)
        .isInstanceOf(IllegalStateException.class);

    assertThat(TenantContext.contextIsSet()).isFalse();
  }
}
