package de.caritas.cob.userservice.api.service.temporaryaccount;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.PasswordPolicy;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Konto behalten" (#1662): a temporary guest turns the account into a permanent one with a
 * password only, at entry or later until the account is deleted. Allowed only when the guest could
 * have chosen a permanent account in the first place: the guest entered under {@link
 * GuestAccountMode#GUEST_CHOOSES} (stored on the account at registration; a Gesprächskreis "join
 * without account" stores the circle's mode, GUEST_CHOOSES by default). A later switch of the link
 * or circle only affects new guests. Accounts created before the mode was stored are judged by
 * their link or circle as it is now.
 *
 * <p>The user row is locked for the whole step. The two deletion jobs lock the same row and
 * re-check the account before they delete it, so whichever comes first wins: a started deletion
 * refuses keeping (409), a kept account is no longer selected or deleted.
 */
@Service
@RequiredArgsConstructor
public class TemporaryAccountKeepService {

  private final @NonNull UserRepository users;
  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull UserChatRepository userChats;
  private final @NonNull IdentityClient identities;

  @Transactional
  public void keep(String userId, String password) {
    if (!PasswordPolicy.isLongEnough(password)) {
      throw new BadRequestException(
          "The password must be at least " + PasswordPolicy.MIN_LENGTH + " characters long");
    }
    var user =
        users
            .findByUserIdForUpdate(userId)
            .orElseThrow(() -> new NotFoundException("Account not found"));
    if (!user.isTemporaryAccount()) {
      throw new ConflictException("The account is not temporary");
    }
    requireNotBeingDeleted(user);
    if (!guestMayChoose(user)) {
      throw new ForbiddenException("This invitation does not let guests keep their account");
    }
    // Inside the row lock: a failure rolls nothing forward, and a retry sets the same password.
    if (!identities.changePassword(userId, password)) {
      throw new InternalServerErrorException("Unable to set the account password");
    }
    user.setTemporaryAccount(false);
    // Neither deletion job selects the account any more: DeleteTemporaryAccountsScheduler reads
    // temporary_account, the ADR-029 expiry job the snapshot.
    user.setTemporaryAccountSnapshot(null);
    users.save(user);
  }

  private static void requireNotBeingDeleted(User user) {
    var snapshot = user.getTemporaryAccountSnapshot();
    if (user.getDeleteDate() != null
        || user.getDeletionLifecycleState() != DeletionLifecycleState.ACTIVE
        || user.getReactivationOperationId() != null
        || (snapshot != null
            && (snapshot.getDeletionStartedAt() != null || snapshot.getDeletedAt() != null))) {
      throw new ConflictException("The account is being deleted");
    }
  }

  /**
   * The stored entry mode decides. Without one (an account from before #1662 stored it): a
   * direct-link or call-link account knows its link through the snapshot; a Gesprächskreis guest
   * has no snapshot and is a member of the circle. A deleted link proves nothing: refused.
   */
  private boolean guestMayChoose(User user) {
    if (user.getGuestAccountModeAtEntry() != null) {
      return user.getGuestAccountModeAtEntry() == GuestAccountMode.GUEST_CHOOSES;
    }
    var snapshot = user.getTemporaryAccountSnapshot();
    if (snapshot != null && snapshot.getInviteLinkId() != null) {
      return links
          .findById(snapshot.getInviteLinkId())
          .map(AgencyInviteLink::effectiveGuestAccountMode)
          .filter(mode -> mode == GuestAccountMode.GUEST_CHOOSES)
          .isPresent();
    }
    return userChats.findByUser(user).stream()
        .map(UserChat::getChat)
        .filter(
            chat ->
                chat != null
                    && de.caritas.cob.userservice.api.facade.ChatConverter.conversationTypeOf(chat)
                        == ConversationType.SELF_HELP)
        .anyMatch(chat -> chat.getGuestAccountMode() == GuestAccountMode.GUEST_CHOOSES);
  }
}
