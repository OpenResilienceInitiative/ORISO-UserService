package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;

/**
 * Outcome of the SHORT transactional step that loads an onboarding invite under its
 * PESSIMISTIC_WRITE row lock and classifies it (#1008 review).
 *
 * <p>Two properties make this a value instead of a plain entity:
 *
 * <ul>
 *   <li>Link death is RETURNED, not thrown. The expiry transition {@code EMAIL_SENT -> EXPIRED} is
 *       persisted in that same transaction and must survive the link-death answer, so the exception
 *       may only leave the flow once the transaction committed. This replaces the former
 *       {@code @Transactional(noRollbackFor = AccountInviteLinkException.class)} trick with a
 *       structural guarantee.
 *   <li>Everything remote — operator DPA text, agency/topic coverage, Keycloak — runs AFTER the
 *       transaction returned this value, so a hanging upstream can no longer pin the invite row or
 *       a database connection for the duration of its timeout.
 * </ul>
 */
record ResolvedOnboardingInvite(
    AccountInvite invite, boolean pendingTwoFactorResume, AccountInviteLinkException linkDeath) {

  /** A deliverable, unexpired invite — the flow continues with its registration step. */
  static ResolvedOnboardingInvite open(AccountInvite invite) {
    return new ResolvedOnboardingInvite(invite, false, null);
  }

  /** A consumed invite that re-enters at the 2FA step (#569 resume contract). */
  static ResolvedOnboardingInvite pendingTwoFactorResume(AccountInvite invite) {
    return new ResolvedOnboardingInvite(invite, true, null);
  }

  /** A dead link; the reason is answered only after the transaction committed. */
  static ResolvedOnboardingInvite dead(AccountInviteLinkException linkDeath) {
    return new ResolvedOnboardingInvite(null, false, linkDeath);
  }

  /** Answers the link death once the transaction committed; no-op for a live invite. */
  void rethrowLinkDeath() {
    if (linkDeath != null) {
      throw linkDeath;
    }
  }
}
