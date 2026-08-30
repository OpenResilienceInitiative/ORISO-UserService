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
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityReactivationRepairWriterTest {

  @InjectMocks private IdentityReactivationRepairWriter writer;
  @Mock private UserRepository userRepository;
  @Mock private IdentityDeactivator identityDeactivator;

  @Test
  void compensateDisablesCurrentGenerationWhileHoldingClaimAndThenReleasesIt() {
    User user = claimedUser("operation-1", DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(writer.compensate(operation("operation-1"), databaseFailure())).isTrue();

    verify(identityDeactivator).deactivateUser("user-1");
    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(user.getReactivationOperationId()).isNull();
    assertThat(user.getReactivationOperationStartedAt()).isNull();
    verify(userRepository).save(user);
  }

  @Test
  void compensateRetainsDurableRepairGenerationWhenKeycloakDisableFails() {
    User user = claimedUser("operation-1", DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));
    doThrow(new IllegalStateException("Keycloak unavailable"))
        .when(identityDeactivator)
        .deactivateUser("user-1");

    assertThrows(
        IdentityReactivationCompensationException.class,
        () -> writer.compensate(operation("operation-1"), databaseFailure()));

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED);
    assertThat(user.getReactivationOperationId()).isEqualTo("operation-1");
    verify(userRepository).save(user);
  }

  @Test
  void lateCompensationCannotDisableIdentityOwnedByNewerOperationGeneration() {
    User user = claimedUser("operation-2", DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(writer.compensate(operation("operation-1"), databaseFailure())).isFalse();

    verifyNoInteractions(identityDeactivator);
    verify(userRepository, never()).save(user);
    assertThat(user.getReactivationOperationId()).isEqualTo("operation-2");
  }

  @Test
  void compensationCannotMakePartialHardDeleteReactivationEligible() {
    User user = claimedUser("operation-1", DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(writer.compensate(operation("operation-1"), databaseFailure())).isFalse();

    verifyNoInteractions(identityDeactivator);
    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
  }

  private User claimedUser(String operationId, DeletionLifecycleState state) {
    var user = new User();
    user.setUserId("user-1");
    user.setDeleteDate(LocalDateTime.now());
    user.setDeletionLifecycleState(state);
    user.setReactivationOperationId(operationId);
    user.setReactivationOperationStartedAt(LocalDateTime.now());
    return user;
  }

  private IdentityReactivationOperation operation(String operationId) {
    return new IdentityReactivationOperation(
        "user-1", operationId, "marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L);
  }

  private RuntimeException databaseFailure() {
    return new IllegalStateException("database commit failed");
  }
}
