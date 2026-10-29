package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.AnonymousUserDeletionUnit;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteTemporaryAccountsService;
import de.caritas.cob.userservice.api.workflow.delete.service.WorkflowErrorMailService;
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
import org.mockito.ArgumentCaptor;
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

  @Mock
  private de.caritas.cob.userservice.api.workflow.delete.service.DirectLinkTemporaryAccountCleanup
      directLinkCleanup;

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
  @SuppressWarnings("unchecked")
  void performDeletionWorkflow_deletesTheNextAccountAndReportsTheFailure_When_OneDeletionFails() {
    givenTheLeaseIsHeld();
    var userRepository = mock(UserRepository.class);
    var deletionUnit = mock(AnonymousUserDeletionUnit.class);
    var errorMail = mock(WorkflowErrorMailService.class);
    var realService = new DeleteTemporaryAccountsService(userRepository, deletionUnit, errorMail);
    setField(realService, "maxAge", Duration.ofDays(1));
    when(userRepository.findTemporaryAccountIdsCreatedBefore(any())).thenReturn(List.of("a", "b"));
    when(deletionUnit.deleteUser("a")).thenThrow(new IllegalStateException("boom"));
    when(deletionUnit.deleteUser("b")).thenReturn(List.of());
    var realScheduler =
        new DeleteTemporaryAccountsScheduler(
            realService,
            tenantContextProvider,
            taskClaimService,
            mock(
                de.caritas.cob.userservice.api.workflow.delete.service
                    .DirectLinkTemporaryAccountCleanup.class));
    setField(realScheduler, "enabled", true);
    setField(realScheduler, "claimDuration", Duration.ofMinutes(30));

    realScheduler.performDeletionWorkflow();

    verify(deletionUnit).deleteUser("b");
    ArgumentCaptor<List<DeletionWorkflowError>> reported = ArgumentCaptor.forClass(List.class);
    verify(errorMail).buildAndSendErrorMail(reported.capture());
    assertThat(reported.getValue())
        .extracting(DeletionWorkflowError::getIdentifier)
        .containsExactly("a");
    verify(taskClaimService).release(LEASE);
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

  // --- direct-link temporary accounts (#1476) ---

  @Test
  void directLinkWorkflow_doesNothing_When_Disabled() {
    setField(scheduler, "enabled", false);

    scheduler.performDirectLinkDeletionWorkflow();

    verifyNoInteractions(taskClaimService, directLinkCleanup);
  }

  @Test
  void directLinkWorkflow_skipsTheRun_When_AnotherReplicaHoldsTheLease() {
    when(taskClaimService.tryClaimLease(anyString(), any())).thenReturn(Optional.empty());

    scheduler.performDirectLinkDeletionWorkflow();

    verifyNoInteractions(directLinkCleanup);
    verify(taskClaimService, never()).release(any());
  }

  @Test
  void directLinkWorkflow_stopsTheBatch_When_TheLeaseIsLost() {
    givenTheLeaseIsHeld();
    when(directLinkCleanup.dueAccountIds(any())).thenReturn(List.of("a", "b"));
    when(taskClaimService.runIfHeld(eq(LEASE), any())).thenReturn(false);

    scheduler.performDirectLinkDeletionWorkflow();

    verify(directLinkCleanup, never()).delete(anyString(), any());
    verify(taskClaimService).runIfHeld(eq(LEASE), any());
    verify(taskClaimService).release(LEASE);
  }

  /** One account that throws must not cost the others their deletion or the error mail. */
  @Test
  @SuppressWarnings("unchecked")
  void directLinkWorkflow_continuesAndReports_When_OneDeletionThrows() {
    givenTheLeaseIsHeld();
    when(directLinkCleanup.dueAccountIds(any())).thenReturn(List.of("a", "b"));
    when(directLinkCleanup.delete(eq("a"), any())).thenThrow(new IllegalStateException("boom"));
    var reported = DeletionWorkflowError.builder().identifier("b").build();
    when(directLinkCleanup.delete(eq("b"), any())).thenReturn(List.of(reported));

    scheduler.performDirectLinkDeletionWorkflow();

    verify(directLinkCleanup).delete(eq("b"), any());
    var errors = ArgumentCaptor.forClass(List.class);
    verify(deleteTemporaryAccountsService).notifyAbout(errors.capture());
    assertThat((List<DeletionWorkflowError>) errors.getValue())
        .extracting(DeletionWorkflowError::getIdentifier)
        .containsExactly("a", "b");
    verify(taskClaimService).release(LEASE);
    assertThat(TenantContext.contextIsSet()).isFalse();
  }

  /** Session timestamps are written in UTC; the expiry comparison must use the same clock. */
  @Test
  void directLinkWorkflow_judgesExpiryInUtc() {
    givenTheLeaseIsHeld();
    var now = ArgumentCaptor.forClass(LocalDateTime.class);
    when(directLinkCleanup.dueAccountIds(now.capture())).thenReturn(List.of());

    scheduler.performDirectLinkDeletionWorkflow();

    var utc = LocalDateTime.now(java.time.ZoneOffset.UTC);
    assertThat(java.time.Duration.between(now.getValue(), utc).abs())
        .isLessThan(java.time.Duration.ofMinutes(1));
  }
}
