package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteTemporaryAccountsService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService.ClaimLease;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeleteTemporaryAccountsSchedulerTest {

  private static final ClaimLease LEASE =
      new ClaimLease("temporary-account-deletion", LocalDateTime.now().plusMinutes(30));

  @InjectMocks private DeleteTemporaryAccountsScheduler scheduler;

  @Mock private DeleteTemporaryAccountsService deleteTemporaryAccountsService;
  @Mock private TenantContextProvider tenantContextProvider;
  @Mock private ScheduledTaskClaimService taskClaimService;

  @BeforeEach
  void setUp() {
    setField(scheduler, "enabled", true);
    setField(scheduler, "claimDuration", Duration.ofMinutes(30));
    lenient()
        .doAnswer(
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

  private void givenTheLeaseIsHeld() {
    when(taskClaimService.tryClaimLease(anyString(), any())).thenReturn(Optional.of(LEASE));
    lenient()
        .when(taskClaimService.runIfHeld(eq(LEASE), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(1).run();
              return true;
            });
  }

  @Test
  void performDeletionWorkflow_deletesEachAccountUnderTheHeldLeaseAndThenReleasesIt() {
    givenTheLeaseIsHeld();
    when(deleteTemporaryAccountsService.expiredAccountIds()).thenReturn(List.of("a", "b"));

    scheduler.performDeletionWorkflow();

    var order = inOrder(taskClaimService, deleteTemporaryAccountsService);
    order.verify(taskClaimService).runIfHeld(eq(LEASE), any());
    order.verify(deleteTemporaryAccountsService).deleteIsolated("a");
    order.verify(taskClaimService).runIfHeld(eq(LEASE), any());
    order.verify(deleteTemporaryAccountsService).deleteIsolated("b");
    order.verify(taskClaimService).release(LEASE);
  }

  @Test
  void performDeletionWorkflow_skipsTheRun_When_AnotherReplicaHoldsTheLease() {
    when(taskClaimService.tryClaimLease(anyString(), any())).thenReturn(Optional.empty());

    scheduler.performDeletionWorkflow();

    verifyNoInteractions(deleteTemporaryAccountsService);
    verify(taskClaimService, never()).release(any());
  }

  @Test
  void performDeletionWorkflow_stopsTheBatch_When_TheLeaseIsLost() {
    givenTheLeaseIsHeld();
    when(deleteTemporaryAccountsService.expiredAccountIds()).thenReturn(List.of("a", "b"));
    when(taskClaimService.runIfHeld(eq(LEASE), any())).thenReturn(false);

    scheduler.performDeletionWorkflow();

    verify(deleteTemporaryAccountsService, never()).deleteIsolated(anyString());
    verify(taskClaimService).runIfHeld(eq(LEASE), any());
  }

  @Test
  void performDeletionWorkflow_releasesTheLeaseAndClearsTheTenant_When_TheDeletionThrows() {
    givenTheLeaseIsHeld();
    when(deleteTemporaryAccountsService.expiredAccountIds()).thenReturn(List.of("a"));
    doThrow(new IllegalStateException("boom"))
        .when(deleteTemporaryAccountsService)
        .deleteIsolated("a");

    assertThatThrownBy(scheduler::performDeletionWorkflow)
        .isInstanceOf(IllegalStateException.class);

    verify(taskClaimService).release(LEASE);
    assertThat(TenantContext.contextIsSet()).isFalse();
  }

  @Test
  void performDeletionWorkflow_clearsTheTenantContext_afterARun() {
    givenTheLeaseIsHeld();

    scheduler.performDeletionWorkflow();

    assertThat(TenantContext.contextIsSet()).isFalse();
  }

  @Test
  void performDeletionWorkflow_doesNothing_When_Disabled() {
    setField(scheduler, "enabled", false);

    scheduler.performDeletionWorkflow();

    verifyNoInteractions(taskClaimService, deleteTemporaryAccountsService);
  }
}
