package de.caritas.cob.userservice.api.admin.service;

import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists reactivation rollback repair state outside the failed caller transaction. */
@Service
@RequiredArgsConstructor
public class IdentityReactivationRepairWriter {

  private final @NonNull UserRepository userRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markRepairRequired(String userId) {
    return userRepository
        .findByUserIdForUpdate(userId)
        .filter(user -> user.getDeleteDate() != null)
        .filter(user -> isRepairableLifecycle(user.getDeletionLifecycleState()))
        .map(
            user -> {
              user.setDeletionLifecycleState(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED);
              userRepository.save(user);
              return true;
            })
        .orElse(false);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean resolveRepair(String userId) {
    return userRepository
        .findByUserIdForUpdate(userId)
        .filter(
            user ->
                user.getDeletionLifecycleState()
                    == DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED)
        .map(
            user -> {
              user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
              userRepository.save(user);
              return true;
            })
        .orElse(false);
  }

  private boolean isRepairableLifecycle(DeletionLifecycleState state) {
    return state != DeletionLifecycleState.HARD_DELETED
        && state != DeletionLifecycleState.HARD_DELETE_IN_PROGRESS
        && state != DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE;
  }
}
