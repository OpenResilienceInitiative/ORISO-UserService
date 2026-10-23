package de.caritas.cob.userservice.api.workflow.delete.action.asker;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.workflow.delete.model.DeletionSourceType.ASKER;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.UserContentCleanup;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionTargetType;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import de.caritas.cob.userservice.api.workflow.delete.service.ScheduledAskerMaintenance.AcknowledgedDeletion;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Action to delete a {@link User} in database. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteDatabaseAskerAction implements ActionCommand<AskerDeletionWorkflowDTO> {

  private final @NonNull UserRepository userRepository;
  private final @NonNull UserChatRepository userChatRepository;
  private final @NonNull UserMobileTokenRepository userMobileTokenRepository;
  private final @NonNull IdentityTombstoneService identityTombstoneService;

  /** Clears existing restricting rows under the same private acknowledgement owner. */
  public Optional<PreparedUserDeletion> prepareAcknowledged(AskerDeletionWorkflowDTO owned) {
    if (owned == null || owned.getAcknowledgedDeletion() == null)
      throw new ScopedIdentityCommandRequiredException();
    var frame = owned.getAcknowledgedDeletion();
    var original = owned.getUser();
    var errors = owned.getDeletionWorkflowErrors();
    frame.requireCurrentUser(original);
    if (errors == null) throw new ScopedIdentityCommandRequiredException();
    var result = new AtomicReference<PreparedUserDeletion>();
    owned.runOwnedLocal(
        () -> {
          if (owned.getUser() != original || owned.getDeletionWorkflowErrors() != errors)
            throw new ScopedIdentityCommandRequiredException();
          if (vetoed(owned, frame)) return;
          if (!deleteChatMemberships(owned) || !deleteMobileTokens(owned)) return;
          var prepared = new PreparedUserDeletion(this, owned, original, frame, errors);
          prepared.requireWithinOwner(frame);
          result.set(prepared);
        });
    return Optional.ofNullable(result.get());
  }

  /** The original User is removed only after this exact action's live preparation revalidates. */
  public boolean completeAcknowledged(PreparedUserDeletion preparation) {
    if (preparation == null || preparation.issuer != this)
      throw new ScopedIdentityCommandRequiredException();
    return preparation.frame.completeOwnedLocalDeletion(
        () -> {
          preparation.requireFor(preparation.frame);
          try {
            identityTombstoneService.recordDeletedUser(preparation.originalUser);
            userRepository.delete(preparation.originalUser);
          } catch (Exception failure) {
            appendError(preparation.target, failure, "Unable to delete user");
          }
        });
  }

  private static boolean vetoed(AskerDeletionWorkflowDTO target, AcknowledgedDeletion frame) {
    return UserContentCleanup.failed(target.getDeletionWorkflowErrors())
        || frame.inactivityCause() && !target.getDeletionWorkflowErrors().isEmpty();
  }

  /** A preparation is data bound to the exact live owner, DTO and original User references. */
  public static final class PreparedUserDeletion {
    private final DeleteDatabaseAskerAction issuer;
    private final AskerDeletionWorkflowDTO target;
    private final User originalUser;
    private final AcknowledgedDeletion frame;
    private final List<DeletionWorkflowError> errors;

    private PreparedUserDeletion(
        DeleteDatabaseAskerAction issuer,
        AskerDeletionWorkflowDTO target,
        User originalUser,
        AcknowledgedDeletion frame,
        List<DeletionWorkflowError> errors) {
      this.issuer = issuer;
      this.target = target;
      this.originalUser = originalUser;
      this.frame = frame;
      this.errors = errors;
    }

    /** Every use rechecks current ownership, collected vetoes and actual SQL prerequisites. */
    public void requireFor(AcknowledgedDeletion acknowledged) {
      if (acknowledged != frame) throw new ScopedIdentityCommandRequiredException();
      if (TransactionSynchronizationManager.isActualTransactionActive())
        requireWithinOwner(acknowledged);
      else frame.runOwnedLocal(() -> requireWithinOwner(acknowledged));
    }

    private void requireWithinOwner(AcknowledgedDeletion acknowledged) {
      if (acknowledged != frame
          || target.getAcknowledgedDeletion() != frame
          || target.getUser() != originalUser
          || target.getDeletionWorkflowErrors() != errors)
        throw new ScopedIdentityCommandRequiredException();
      frame.requireCurrentUser(originalUser);
      if (vetoed(target, frame)) throw new ScopedIdentityCommandRequiredException();
      var chats = issuer.userChatRepository.findByUser(originalUser);
      var tokens = issuer.userMobileTokenRepository.findByUser(originalUser);
      if (chats == null || !chats.isEmpty() || tokens == null || !tokens.isEmpty())
        throw new ScopedIdentityCommandRequiredException();
      frame.requireCurrentUser(originalUser);
    }
  }

  /**
   * Deletes the given {@link User} in database, together with the dependencies that hold a
   * restricting foreign key on it.
   *
   * <p>{@code user_chat} and {@code user_mobile_token} both reference {@code user} with {@code ON
   * DELETE RESTRICT} and are not cascaded by JPA, so they have to go first or the user delete
   * fails. The chat itself is deliberately left alone — a group chat outlives any single
   * participant.
   *
   * <p>The row is also kept when clearing the user's unencrypted content (drafts, notification
   * feed) failed earlier in the workflow. That content is keyed by the user, so deleting the
   * account row would orphan it beyond any retry — the row is what the next scheduler run needs to
   * try again (#983, KDG epic #1010).
   *
   * @param actionTarget the {@link AskerDeletionWorkflowDTO} with the {@link User} to delete
   */
  @Override
  public void execute(AskerDeletionWorkflowDTO actionTarget) {
    if (UserContentCleanup.failed(actionTarget.getDeletionWorkflowErrors())) {
      log.warn("Keeping user account row: unencrypted user content could not be deleted");
      return;
    }
    if (!deleteChatMemberships(actionTarget) || !deleteMobileTokens(actionTarget)) {
      return;
    }

    try {
      identityTombstoneService.recordDeletedUser(actionTarget.getUser());
      this.userRepository.delete(actionTarget.getUser());
    } catch (Exception e) {
      log.error("UserService delete workflow error: ", e);
      actionTarget
          .getDeletionWorkflowErrors()
          .add(
              DeletionWorkflowError.builder()
                  .deletionSourceType(ASKER)
                  .deletionTargetType(DeletionTargetType.DATABASE)
                  .identifier(actionTarget.getUser().getUserId())
                  .reason("Unable to delete user")
                  .timestamp(nowInUtc())
                  .build());
    }
  }

  private boolean deleteChatMemberships(AskerDeletionWorkflowDTO actionTarget) {
    try {
      var userChats = this.userChatRepository.findByUser(actionTarget.getUser());
      this.userChatRepository.deleteAll(userChats);
      return true;
    } catch (Exception e) {
      appendError(actionTarget, e, "Could not delete user chat memberships");
      return false;
    }
  }

  private boolean deleteMobileTokens(AskerDeletionWorkflowDTO actionTarget) {
    try {
      var mobileTokens = this.userMobileTokenRepository.findByUser(actionTarget.getUser());
      this.userMobileTokenRepository.deleteAll(mobileTokens);
      return true;
    } catch (Exception e) {
      appendError(actionTarget, e, "Could not delete user mobile tokens");
      return false;
    }
  }

  private void appendError(AskerDeletionWorkflowDTO actionTarget, Exception e, String reason) {
    log.error("UserService delete workflow error: ", e);
    actionTarget
        .getDeletionWorkflowErrors()
        .add(
            DeletionWorkflowError.builder()
                .deletionSourceType(ASKER)
                .deletionTargetType(DeletionTargetType.DATABASE)
                .identifier(actionTarget.getUser().getUserId())
                .reason(reason)
                .timestamp(nowInUtc())
                .build());
  }
}
