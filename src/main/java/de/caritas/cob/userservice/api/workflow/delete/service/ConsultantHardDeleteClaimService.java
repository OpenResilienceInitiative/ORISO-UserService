package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes consultant hard deletion against the admin cancel (#123): the claim takes the same row
 * lock as {@code cancelConsultantDeletion}, and once a row is claimed its state is outside the soft
 * stage, so a cancel is refused from then on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantHardDeleteClaimService {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Consultant> claim(String consultantId) {
    var locked = consultantRepository.findPictureOwnerForUpdate(consultantId);
    if (locked.isEmpty() || locked.get().getDeleteDate() == null) {
      return Optional.empty();
    }
    Consultant consultant = deletionLifecycleService.normalizeConsultantLifecycle(locked.get());
    boolean retryingPartialFailure =
        consultant.getDeletionLifecycleState()
            == DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE;
    if (!retryingPartialFailure && !deletionLifecycleService.isReadyForHardDelete(consultant)) {
      consultantRepository.save(consultant);
      return Optional.empty();
    }
    consultant.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_IN_PROGRESS);
    return Optional.of(consultantRepository.save(consultant));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(String consultantId) {
    int released =
        consultantRepository.releaseConsultantHardDeleteClaim(
            consultantId,
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    if (released == 1) {
      log.warn(
          "Released unfinished consultant hard-delete claim for consultantId={}", consultantId);
    }
  }

  /**
   * Recovers a claim left by a crashed workflow after the scheduler has acquired its global lease.
   * An irreversible external deletion may already have happened, so the row never becomes
   * cancellable again.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int releaseInterruptedClaims() {
    int released =
        consultantRepository.releaseInterruptedConsultantHardDeleteClaims(
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    if (released > 0) {
      log.warn("Recovered {} interrupted consultant hard-delete claim(s)", released);
    }
    return released;
  }
}
