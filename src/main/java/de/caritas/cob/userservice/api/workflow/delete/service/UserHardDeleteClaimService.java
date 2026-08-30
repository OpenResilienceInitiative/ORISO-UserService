package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes asker hard deletion against privileged cancellation. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserHardDeleteClaimService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<User> claim(String userId) {
    var locked = userRepository.findByUserIdForUpdate(userId);
    if (locked.isEmpty() || locked.get().getDeleteDate() == null) {
      return Optional.empty();
    }
    User user = deletionLifecycleService.normalizeUserLifecycle(locked.get());
    if (!deletionLifecycleService.isReadyForHardDelete(user)) {
      userRepository.save(user);
      return Optional.empty();
    }
    user.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
    return Optional.of(userRepository.save(user));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(String userId) {
    int released =
        userRepository.releaseUserHardDeleteClaim(
            userId,
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    if (released == 1) {
      log.warn("Released unfinished asker hard-delete claim for userId={}", userId);
    }
  }

  /**
   * Recovers a claim left by a crashed workflow after the scheduler has acquired its global lease.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int releaseInterruptedClaims() {
    int released =
        userRepository.releaseInterruptedUserHardDeleteClaims(
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    if (released > 0) {
      log.warn("Recovered {} interrupted asker hard-delete claim(s)", released);
    }
    return released;
  }
}
