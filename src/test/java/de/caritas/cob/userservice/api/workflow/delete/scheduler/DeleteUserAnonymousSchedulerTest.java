package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAnonymousService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DeleteUserAnonymousSchedulerTest {

  @InjectMocks private DeleteUserAnonymousScheduler deleteUserAnonymousScheduler;

  @Mock private DeleteUserAnonymousService deleteUserAnonymousService;

  @Mock private TenantContextProvider tenantContextProvider;

  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(deleteUserAnonymousScheduler, "claimDuration", Duration.ofMinutes(30));
  }

  @Test
  public void performDeletionWorkflow_Should_executeDeleteInactiveAnonymousUsers() {
    when(taskClaimService.tryClaim("anonymous-user-deletion", Duration.ofMinutes(30)))
        .thenReturn(true);

    this.deleteUserAnonymousScheduler.performDeletionWorkflow();

    verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    verify(this.deleteUserAnonymousService).deleteInactiveAnonymousUsers();
  }

  @Test
  void performDeletionWorkflow_Should_skipAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim("anonymous-user-deletion", Duration.ofMinutes(30)))
        .thenReturn(false);

    deleteUserAnonymousScheduler.performDeletionWorkflow();

    verifyNoInteractions(tenantContextProvider, deleteUserAnonymousService);
  }
}
