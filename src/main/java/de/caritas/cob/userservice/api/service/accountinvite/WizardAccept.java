package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.AccountInvite;
import java.util.Objects;

/**
 * What the onboarding wizard decided from its first, unlocked read of the invite, and the
 * Beratungsstelle it founds. The accept re-checks it under the row lock (ORISO-Admin#1026).
 *
 * @param createUnit runs after the row is held and before any account exists
 */
public record WizardAccept(
    AccountInviteTargetRole role, Boolean alsoCounsellor, Runnable createUnit) {

  /** A plain accept outside the wizard: nothing decided up front, nothing to found. */
  public static final WizardAccept NONE = new WizardAccept(null, null, () -> {});

  public static WizardAccept decidedFrom(AccountInvite read, Runnable createUnit) {
    return new WizardAccept(read.getTargetRole(), read.getAlsoCounsellor(), createUnit);
  }

  /** 409 INVITE_CHANGED when the role the wizard routed by changed before the lock. */
  void requireUnchanged(AccountInvite locked) {
    if (role == null || locked.getStatus() != AccountInviteStatus.EMAIL_SENT) {
      return;
    }
    if (locked.getTargetRole() != role
        || !Objects.equals(locked.getAlsoCounsellor(), alsoCounsellor)) {
      throw InviteRowHold.conflict(HttpStatusExceptionReason.INVITE_CHANGED);
    }
  }
}
