package de.caritas.cob.userservice.api.service.livechat;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Idempotently completes a committed normal-finish identity disable. */
@Service
@RequiredArgsConstructor
public class LiveChatFinishExecutor {
  private final @NonNull LiveChatInactivityFence inactivity;
  private final @NonNull UserRepository users;
  private final @NonNull LiveChatAccountClaimRepository records;
  private final @NonNull IdentityDeactivator identities;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void disableAndMarkFinished(String userId) {
    inactivity.requireActive(userId);
    var user =
        users
            .findByUserIdForUpdate(userId)
            .orElseThrow(() -> new ConflictException("Account no longer exists"));
    if (user.getDeleteDate() != null
        || user.getDeletionLifecycleState()
            != de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState.ACTIVE
        || user.getReactivationOperationId() != null)
      throw new ConflictException("Account lifecycle action prevents finishing");
    var claim =
        records.findForUpdate(userId).orElseThrow(() -> new ConflictException("No finish claim"));
    if (claim.getLifecycle() == LiveChatAccountLifecycle.ANONYMOUS_FINISHED) return;
    if (claim.getLifecycle() != LiveChatAccountLifecycle.FINISH_PENDING)
      throw new ConflictException("Finish claim no longer current");
    identities.deactivateUser(userId);
    claim.setLifecycle(LiveChatAccountLifecycle.ANONYMOUS_FINISHED);
    records.save(claim);
  }
}
