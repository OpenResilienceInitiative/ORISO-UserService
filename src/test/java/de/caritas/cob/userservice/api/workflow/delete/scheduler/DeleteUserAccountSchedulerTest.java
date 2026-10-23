package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.tenant.TenantContextProvider;
import de.caritas.cob.userservice.api.workflow.delete.service.DeleteUserAccountService;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledConsultantMaintenance;
import de.caritas.cob.userservice.api.workflow.delete.service.UserHardDeleteClaimService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@ExtendWith(MockitoExtension.class)
public class DeleteUserAccountSchedulerTest {

  @InjectMocks private DeleteUserAccountScheduler deleteUserAccountScheduler;

  @Mock private DeleteUserAccountService deleteUserAccountService;

  @Mock private TenantContextProvider tenantContextProvider;

  @Mock private ScheduledTaskClaimService taskClaimService;

  @Mock private UserHardDeleteClaimService userHardDeleteClaimService;

  @Mock private IdentityReactivationRepairService identityReactivationRepairService;

  @Mock private ScheduledConsultantMaintenance scopedConsultants;

  @BeforeEach
  void setUp() {
    setField(deleteUserAccountScheduler, "claimDuration", Duration.ofHours(12));
  }

  @Test
  public void performDeletionWorkflow_Should_executeDeleteUserAccounts() {
    when(taskClaimService.tryClaim("account-deletion", Duration.ofHours(12))).thenReturn(true);

    this.deleteUserAccountScheduler.performDeletionWorkflow();

    // Order is the contract, not just the call set: an outstanding repair must be retried and an
    // interrupted claim released BEFORE deletion runs, or the run deletes against a claim state it
    // has not recovered yet. Independent verifies pass even if deletion goes first.
    InOrder inOrder =
        inOrder(
            tenantContextProvider,
            identityReactivationRepairService,
            userHardDeleteClaimService,
            deleteUserAccountService);
    inOrder.verify(tenantContextProvider).setTechnicalContextIfMultiTenancyIsEnabled();
    inOrder.verify(identityReactivationRepairService).retryOutstandingRepairs();
    inOrder.verify(userHardDeleteClaimService).releaseInterruptedClaims();
    inOrder.verify(deleteUserAccountService).deleteUserAccounts();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  void performDeletionWorkflow_Should_skipAllDownstreamCalls_When_claimIsLost() {
    when(taskClaimService.tryClaim("account-deletion", Duration.ofHours(12))).thenReturn(false);

    deleteUserAccountScheduler.performDeletionWorkflow();

    verifyNoInteractions(
        tenantContextProvider,
        identityReactivationRepairService,
        userHardDeleteClaimService,
        deleteUserAccountService);
  }

  @Test
  void fiveActorsRunsBothScopedAccountKindsWithoutEnteringLegacyRepairOrDeletion() {
    var askers = mock(ScheduledAskerMaintenance.class);
    setField(deleteUserAccountScheduler, "taskIdentityModel", "FIVE_ACTORS");
    var dependencies = new DefaultListableBeanFactory();
    dependencies.registerSingleton("askerMaintenance", askers);
    dependencies.registerSingleton("consultantMaintenance", scopedConsultants);
    var injection = new AutowiredAnnotationBeanPostProcessor();
    injection.setAutowiredAnnotationType(Autowired.class);
    injection.setBeanFactory(dependencies);
    injection.processInjection(deleteUserAccountScheduler);

    assertDoesNotThrow(deleteUserAccountScheduler::verifyScopedMaintenanceAvailability);
    deleteUserAccountScheduler.performDeletionWorkflow();

    var ordered = inOrder(askers, scopedConsultants);
    ordered.verify(askers).performDeletionWorkflow();
    ordered.verify(scopedConsultants).performDeletionWorkflow();
    ordered.verifyNoMoreInteractions();
    verifyNoInteractions(
        taskClaimService,
        tenantContextProvider,
        identityReactivationRepairService,
        userHardDeleteClaimService,
        deleteUserAccountService);
  }

  @Test
  void fiveActorsRefusesStartupWhenOnlyTheAskerOwnerIsPresent() {
    var incomplete =
        new DeleteUserAccountScheduler(
            deleteUserAccountService,
            tenantContextProvider,
            taskClaimService,
            userHardDeleteClaimService,
            identityReactivationRepairService);
    setField(incomplete, "taskIdentityModel", "FIVE_ACTORS");
    setField(incomplete, "scopedMaintenance", mock(ScheduledAskerMaintenance.class));

    assertThrows(IllegalStateException.class, incomplete::verifyScopedMaintenanceAvailability);
    verifyNoInteractions(
        taskClaimService,
        tenantContextProvider,
        identityReactivationRepairService,
        userHardDeleteClaimService,
        deleteUserAccountService);
  }
}
