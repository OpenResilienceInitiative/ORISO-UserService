package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

/**
 * Every writer that changes an invite takes its row here first, so a racing revoke or accept either
 * waits or is seen: nobody writes over a status they did not check (ORISO-Admin#1026). A lock that
 * cannot be had in time answers 409 INVITE_BUSY instead of a 500.
 */
final class InviteRowHold {

  private InviteRowHold() {}

  /** The invite under a row lock held until commit. */
  static Optional<AccountInvite> lock(AccountInviteRepository repository, Long inviteId) {
    return orBusy(() -> repository.findByIdForUpdate(inviteId));
  }

  /** The invite of a link token under a row lock held until commit. */
  static Optional<AccountInvite> lockByToken(AccountInviteRepository repository, String tokenHash) {
    return orBusy(() -> repository.findByTokenHash(tokenHash));
  }

  /** Holds the row until commit; 409 INVITE_NOT_PENDING if its status moved since it was read. */
  static void hold(AccountInviteRepository repository, AccountInvite invite, LocalDateTime now) {
    if (orBusy(() -> repository.holdInStatus(invite.getId(), invite.getStatus(), now)) != 1) {
      throw conflict(HttpStatusExceptionReason.INVITE_NOT_PENDING);
    }
  }

  static <T> T orBusy(Supplier<T> lockingCall) {
    try {
      return lockingCall.get();
    } catch (PessimisticLockingFailureException stillHeld) {
      throw conflict(HttpStatusExceptionReason.INVITE_BUSY);
    }
  }

  static CustomValidationHttpStatusException conflict(HttpStatusExceptionReason reason) {
    return new CustomValidationHttpStatusException(reason, HttpStatus.CONFLICT);
  }
}
