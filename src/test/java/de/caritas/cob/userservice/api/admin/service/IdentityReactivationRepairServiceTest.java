package de.caritas.cob.userservice.api.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IdentityReactivationRepairServiceTest {

  @Mock private IdentityReactivationRepairWriter repairWriter;
  @Mock private UserRepository userRepository;

  private SimpleMeterRegistry meterRegistry;
  private IdentityReactivationRepairService repairService;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    repairService =
        new IdentityReactivationRepairService(repairWriter, userRepository, meterRegistry);
    ReflectionTestUtils.setField(repairService, "staleAfter", Duration.ofMinutes(5));
    ReflectionTestUtils.setField(repairService, "repairBatchSize", 200);
  }

  @Test
  void compensateResolvesCurrentGeneration() {
    when(repairWriter.compensate(org.mockito.ArgumentMatchers.eq(operation()), any()))
        .thenReturn(true);

    repairService.compensate(operation(), databaseFailure());

    assertThat(counter("attempt")).isEqualTo(1.0);
    assertThat(counter("disabled")).isEqualTo(1.0);
  }

  @Test
  void compensateSkipsAStaleGenerationWithoutTouchingNewerIdentity() {
    when(repairWriter.compensate(org.mockito.ArgumentMatchers.eq(operation()), any()))
        .thenReturn(false);

    repairService.compensate(operation(), databaseFailure());

    assertThat(counter("stale_skipped")).isEqualTo(1.0);
  }

  @Test
  void compensateSurfacesFailedDisableWhileDurableClaimRemains() {
    var failure = compensationFailure();
    doThrow(failure)
        .when(repairWriter)
        .compensate(org.mockito.ArgumentMatchers.eq(operation()), any());

    assertThrows(
        IdentityReactivationCompensationException.class,
        () -> repairService.compensate(operation(), databaseFailure()));

    assertThat(counter("queued")).isEqualTo(1.0);
  }

  @Test
  void compensateSurfacesPersistenceFailureWhenDurableMarkerCannotBeUpdated() {
    var persistenceFailure = new IllegalStateException("row lock timeout");
    doThrow(persistenceFailure)
        .when(repairWriter)
        .compensate(org.mockito.ArgumentMatchers.eq(operation()), any());

    RuntimeException actual =
        assertThrows(
            IllegalStateException.class,
            () -> repairService.compensate(operation(), databaseFailure()));

    assertThat(actual).isSameAs(persistenceFailure);
    assertThat(counter("queue_failed")).isEqualTo(1.0);
  }

  @Test
  void retryOutstandingRepairsRetriesExplicitRepairImmediately() {
    User repair = claimedUser(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED, 0);
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED, PageRequest.of(0, 200)))
        .thenReturn(List.of(repair));
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_IN_PROGRESS))
        .thenReturn(List.of());
    when(repairWriter.retry("user-1", "operation-1")).thenReturn(true);

    repairService.retryOutstandingRepairs();

    verify(repairWriter).retry("user-1", "operation-1");
    assertThat(counter("retry_resolved")).isEqualTo(1.0);
  }

  @Test
  void retryOutstandingRepairsClosesCrashWindowOnlyAfterClaimIsStale() {
    User stale = claimedUser(DeletionLifecycleState.REACTIVATION_IN_PROGRESS, 10);
    User fresh = claimedUser(DeletionLifecycleState.REACTIVATION_IN_PROGRESS, 1);
    fresh.setUserId("user-2");
    fresh.setReactivationOperationId("operation-2");
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED, PageRequest.of(0, 200)))
        .thenReturn(List.of());
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_IN_PROGRESS))
        .thenReturn(List.of(stale, fresh));
    when(repairWriter.retry("user-1", "operation-1")).thenReturn(true);

    repairService.retryOutstandingRepairs();

    verify(repairWriter).retry("user-1", "operation-1");
    verify(repairWriter, never()).retry("user-2", "operation-2");
  }

  @Test
  void retryOutstandingRepairsKeepsFailedRepairObservable() {
    User repair = claimedUser(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED, 0);
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED, PageRequest.of(0, 200)))
        .thenReturn(List.of(repair));
    when(userRepository.findAllByDeletionLifecycleStateOrderByCreateDateAsc(
            DeletionLifecycleState.REACTIVATION_IN_PROGRESS))
        .thenReturn(List.of());
    doThrow(compensationFailure()).when(repairWriter).retry("user-1", "operation-1");

    repairService.retryOutstandingRepairs();

    assertThat(counter("retry_failed")).isEqualTo(1.0);
  }

  private User claimedUser(DeletionLifecycleState state, long ageMinutes) {
    var user = new User();
    user.setUserId("user-1");
    user.setDeletionLifecycleState(state);
    user.setReactivationOperationId("operation-1");
    user.setReactivationOperationStartedAt(
        LocalDateTime.now(ZoneOffset.UTC).minusMinutes(ageMinutes));
    return user;
  }

  private IdentityReactivationOperation operation() {
    return new IdentityReactivationOperation(
        "user-1", "operation-1", "marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L);
  }

  private RuntimeException databaseFailure() {
    return new IllegalStateException("database commit failed");
  }

  private IdentityReactivationCompensationException compensationFailure() {
    return new IdentityReactivationCompensationException(
        "Keycloak rollback failed",
        databaseFailure(),
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
