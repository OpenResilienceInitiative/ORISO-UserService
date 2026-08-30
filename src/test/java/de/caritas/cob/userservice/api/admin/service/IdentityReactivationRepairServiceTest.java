package de.caritas.cob.userservice.api.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityReactivationRepairServiceTest {

  @Mock private IdentityDeactivator identityDeactivator;
  @Mock private IdentityReactivationRepairWriter repairWriter;
  @Mock private UserRepository userRepository;

  private SimpleMeterRegistry meterRegistry;
  private IdentityReactivationRepairService repairService;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    repairService =
        new IdentityReactivationRepairService(
            identityDeactivator, repairWriter, userRepository, meterRegistry);
  }

  @Test
  void compensateCompletesWithoutRepairStateWhenDisableSucceeds() {
    repairService.compensate("user-1", new IllegalStateException("database rollback"));

    verify(identityDeactivator).deactivateUser("user-1");
    verifyNoInteractions(repairWriter);
    assertThat(counter("disabled")).isEqualTo(1.0);
  }

  @Test
  void compensateSurfacesTypedFailureWithoutWritingInsideCallerTransaction() {
    doThrow(new IllegalStateException("Keycloak unavailable"))
        .when(identityDeactivator)
        .deactivateUser("user-1");
    assertThrows(
        IdentityReactivationCompensationException.class,
        () -> repairService.compensate("user-1", new IllegalStateException("database rollback")));

    verifyNoInteractions(repairWriter);
  }

  @Test
  void queueFailedCompensationPersistsRepairAfterCallerTransactionCompletion() {
    var failure = compensationFailure();
    when(repairWriter.markRepairRequired("user-1")).thenReturn(true);

    repairService.queueFailedCompensation("user-1", failure);

    verify(repairWriter).markRepairRequired("user-1");
    assertThat(counter("queued")).isEqualTo(1.0);
  }

  @Test
  void queueFailedCompensationSurfacesWhenDurableRepairCannotBePersisted() {
    when(repairWriter.markRepairRequired("user-1")).thenReturn(false);

    assertThrows(
        IdentityReactivationCompensationException.class,
        () -> repairService.queueFailedCompensation("user-1", compensationFailure()));

    assertThat(counter("queue_failed")).isEqualTo(1.0);
  }

  @Test
  void queueFailedCompensationRemainsObservableWhenRepairWriterFails() {
    doThrow(new IllegalStateException("database unavailable"))
        .when(repairWriter)
        .markRepairRequired("user-1");

    assertThrows(
        IdentityReactivationCompensationException.class,
        () -> repairService.queueFailedCompensation("user-1", compensationFailure()));

    assertThat(counter("queue_failed")).isEqualTo(1.0);
  }

  @Test
  void retryOutstandingRepairsDisablesAndResolvesDurableState() {
    User user = repairUser();
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED))
        .thenReturn(List.of(user));
    when(repairWriter.resolveRepair("user-1")).thenReturn(true);

    repairService.retryOutstandingRepairs();

    verify(identityDeactivator).deactivateUser("user-1");
    verify(repairWriter).resolveRepair("user-1");
    assertThat(counter("retry_resolved")).isEqualTo(1.0);
  }

  @Test
  void retryOutstandingRepairsLeavesDurableStateWhenKeycloakStillFails() {
    User user = repairUser();
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED))
        .thenReturn(List.of(user));
    doThrow(new IllegalStateException("Keycloak unavailable"))
        .when(identityDeactivator)
        .deactivateUser("user-1");

    repairService.retryOutstandingRepairs();

    verify(repairWriter, never()).resolveRepair("user-1");
    assertThat(counter("retry_failed")).isEqualTo(1.0);
  }

  private User repairUser() {
    var user = new User();
    user.setUserId("user-1");
    user.setDeletionLifecycleState(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED);
    return user;
  }

  private IdentityReactivationCompensationException compensationFailure() {
    return new IdentityReactivationCompensationException(
        "Keycloak rollback failed",
        new IllegalStateException("database rollback"),
        new IllegalStateException("Keycloak unavailable"));
  }

  private double counter(String outcome) {
    return meterRegistry
        .get(IdentityReactivationRepairService.REPAIR_METRIC)
        .tag("outcome", outcome)
        .counter()
        .count();
  }
}
