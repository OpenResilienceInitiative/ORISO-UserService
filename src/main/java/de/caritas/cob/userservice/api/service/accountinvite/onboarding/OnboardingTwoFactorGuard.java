package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The one place that decides whether an onboarding invite may still carry its two-factor step
 * (#1030 review).
 *
 * <p>{@link CounsellorOnboardingService} and {@link TenantAdminOnboardingService} run the same
 * resume/link-death state machine, and both had character-identical private copies of the three
 * methods below. That duplication was safe only as long as nobody touched it: {@link
 * #revalidateTwoFactorGate} is what stands between a verified one-time password and an activated
 * gate, so a change applied to one flow but not the other would silently weaken the second. Both
 * flows now answer with the same link-death and 400 contract BY CONSTRUCTION.
 *
 * <p>Deliberately stateless and static: every method decides from the invite row alone and writes
 * nothing. The expiry transition {@code EMAIL_SENT -> EXPIRED} is NOT here — it persists through
 * each service's own repository, belongs to the registration/resolve step rather than to the gate,
 * and its whole contract is that the write commits before the exception leaves the flow.
 */
final class OnboardingTwoFactorGuard {

  private OnboardingTwoFactorGuard() {}

  /**
   * Closes the time-of-check/time-of-use window of the 2FA gate. The eligibility check of the
   * activation endpoint and the write that activates the gate are separated by the Keycloak round
   * trip, which deliberately runs with NO row lock held — so in between the invite may have been
   * revoked or superseded, may have passed its expiry, may already have had its gate activated, may
   * have been re-accepted by a different acceptor, or may have had its pending secret rotated.
   * Activating the gate anyway would honour a one-time password that was verified against state
   * which no longer exists.
   *
   * <p>Call on the FRESHLY LOCKED row from inside the writing transaction, before anything is
   * written. The rejections mirror the precondition ladder of both activation endpoints exactly — a
   * link that dies mid-flow is indistinguishable from a link that was already dead when the request
   * arrived, and no new status code or reason is introduced. Nothing has been written when this
   * throws, and no expiry transition applies to an {@code ACCEPTED} row, so the rollback of the
   * surrounding transaction loses nothing.
   *
   * @param locked the invite row as it is RIGHT NOW, re-read under its PESSIMISTIC_WRITE lock
   * @param verifiedAcceptorId the acceptor the one-time password was actually verified for
   * @param verifiedPendingSecret the pending secret the one-time password was actually verified
   *     against
   */
  static void revalidateTwoFactorGate(
      AccountInvite locked, String verifiedAcceptorId, String verifiedPendingSecret) {
    if (locked.getStatus() != AccountInviteStatus.ACCEPTED) {
      // Revoked, superseded or expired in the meantime — the link's own death reason.
      throw linkDeathException(locked);
    }
    if (!isResumableAtTwoFactorStep(locked, LocalDateTime.now())) {
      // Gate satisfied in the meantime or the resume window closed — terminally consumed.
      throw new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
    }
    if (!Objects.equals(locked.getAcceptedByUserId(), verifiedAcceptorId)
        || !Objects.equals(locked.getTotpPendingSecret(), verifiedPendingSecret)) {
      // A different acceptor now owns the invite, or the pending secret was rotated: the setup
      // this code was verified against is gone — same answer the precondition check gives for a
      // row without usable setup material.
      throw new BadRequestException("No pending TOTP setup exists for this invite");
    }
  }

  /**
   * Whether a consumed invite may still re-enter at the 2FA step (#569 resume contract): the link
   * is {@code ACCEPTED}, its mandatory activation is still pending and it is inside its expiry
   * window. An invite without an expiry date never leaves that window.
   */
  static boolean isResumableAtTwoFactorStep(AccountInvite invite, LocalDateTime now) {
    boolean twoFactorStillPending =
        !AccountInviteService.isTwoFactorGateSatisfied(invite.getTwoFactorStatus());
    boolean withinExpiryWindow =
        invite.getExpiresAt() == null || !invite.getExpiresAt().isBefore(now);
    return invite.getStatus() == AccountInviteStatus.ACCEPTED
        && twoFactorStillPending
        && withinExpiryWindow;
  }

  /**
   * The distinct link-death reason a status maps to (TEN-INV-U6, #890): public frontends render a
   * dedicated terminal state per reason, so this stays a total mapping — anything not terminal in
   * its own right is answered as {@code NOT_ACTIVE}.
   */
  static AccountInviteLinkException linkDeathException(AccountInvite invite) {
    return switch (invite.getStatus()) {
      case ACCEPTED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.CONSUMED);
      case REVOKED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.REVOKED);
      case SUPERSEDED ->
          new AccountInviteLinkException(AccountInviteLinkException.Reason.SUPERSEDED);
      case EXPIRED -> new AccountInviteLinkException(AccountInviteLinkException.Reason.EXPIRED);
      default -> new AccountInviteLinkException(AccountInviteLinkException.Reason.NOT_ACTIVE);
    };
  }
}
