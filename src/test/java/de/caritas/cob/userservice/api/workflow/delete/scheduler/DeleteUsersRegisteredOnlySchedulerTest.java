package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUsersRegisteredOnlyService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DeleteUsersRegisteredOnlySchedulerTest {

  @InjectMocks DeleteUsersRegisteredOnlyScheduler deleteUsersRegisteredOnlyScheduler;

  @Mock DeleteUsersRegisteredOnlyService deleteUsersRegisteredOnlyService;

  @Mock TenantContextProvider tenantContextProvider;

  @Mock ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(deleteUsersRegisteredOnlyScheduler, "claimDuration", Duration.ofHours(12));
  }

  @Test
  public void
      performDeletionWorkflow_Should_executeDeleteUserAccountsTimeSensitive_WhenFeatureIsEnabled() {
    setField(deleteUsersRegisteredOnlyScheduler, "userRegisteredOnlyDeleteWorkflowEnabled", true);
    when(taskClaimService.tryClaim("registered-only-user-deletion", Duration.ofHours(12)))
        .thenReturn(true);
    deleteUsersRegisteredOnlyScheduler.performDeletionWorkflow();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(deleteUsersRegisteredOnlyService).deleteUserAccountsTimeSensitive();
  }

  @Test
  public void
      performDeletionWorkflow_ShouldNot_executeDeleteUserAccountsTimeSensitive_WhenFeatureIsDisabled() {
    setField(deleteUsersRegisteredOnlyScheduler, "userRegisteredOnlyDeleteWorkflowEnabled", false);
    deleteUsersRegisteredOnlyScheduler.performDeletionWorkflow();

    verifyNoInteractions(tenantContextProvider, deleteUsersRegisteredOnlyService, taskClaimService);
  }

  @Test
  public void
      performDeletionWorkflow_Should_executeDeleteUserAccountsTimeInsensitive_WhenFeatureIsEnabled() {
    setField(
        deleteUsersRegisteredOnlyScheduler,
        "userRegisteredOnlyDeleteWorkflowAfterSessionPurgeEnabled",
        true);
    when(taskClaimService.tryClaim("registered-only-user-deletion", Duration.ofHours(12)))
        .thenReturn(true);
    deleteUsersRegisteredOnlyScheduler.performDeletionWorkflow();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(deleteUsersRegisteredOnlyService).deleteUserAccountsTimeInsensitive();
  }

  @Test
  public void
      performDeletionWorkflow_ShouldNot_executeDeleteUserAccountsTimeInsensitive_WhenFeatureIsDisabled() {
    setField(
        deleteUsersRegisteredOnlyScheduler,
        "userRegisteredOnlyDeleteWorkflowAfterSessionPurgeEnabled",
        false);
    deleteUsersRegisteredOnlyScheduler.performDeletionWorkflow();

    verifyNoInteractions(tenantContextProvider, deleteUsersRegisteredOnlyService, taskClaimService);
  }
}
