package de.caritas.cob.userservice.api.admin.service;

import de.caritas.cob.userservice.api.exception.identity.IdentityReactivationCompensationException;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Performs generation-fenced fail-closed identity repair while holding the user-row lock. */
@Service
@RequiredArgsConstructor
public class IdentityReactivationRepairWriter {

  private final @NonNull UserRepository userRepository;
  private final @NonNull IdentityDeactivator identityDeactivator;

  /**
   * Disables the exact claimed identity before releasing its operation generation. A newer
   * operation cannot start while this transaction owns the pessimistic row lock.
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      noRollbackFor = IdentityReactivationCompensationException.class)
  public boolean compensate(
      IdentityReactivationOperation operation, RuntimeException reactivationFailure) {
    return repairCurrentOperation(operation.userId(), operation.operationId(), reactivationFailure);
  }

  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      noRollbackFor = IdentityReactivationCompensationException.class)
  public boolean retry(String userId, String operationId) {
    return repairCurrentOperation(
        userId,
        operationId,
        new IllegalStateException("Recovering stale durable identity reactivation claim"));
  }

  private boolean repairCurrentOperation(
      String userId, String operationId, RuntimeException reactivationFailure) {
    User user =
        userRepository
            .findByUserIdForUpdate(userId)
            .filter(
                candidate ->
                    IdentityReactivationSagaStore.isCurrentOperation(candidate, operationId))
            .filter(this::isRepairableState)
            .orElse(null);
    if (user == null) {
      return false;
    }

    try {
      identityDeactivator.deactivateUser(user.getUserId());
    } catch (RuntimeException compensationFailure) {
      user.setDeletionLifecycleState(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED);
      userRepository.save(user);
      throw new IdentityReactivationCompensationException(
          "Keycloak rollback failed; durable repair claim retained",
          reactivationFailure,
          compensationFailure);
    }
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    IdentityReactivationSagaStore.clearOperation(user);
    userRepository.save(user);
    return true;
  }

  private boolean isRepairableState(User user) {
    return user.getDeletionLifecycleState() == DeletionLifecycleState.REACTIVATION_IN_PROGRESS
        || user.getDeletionLifecycleState() == DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED;
  }
}
