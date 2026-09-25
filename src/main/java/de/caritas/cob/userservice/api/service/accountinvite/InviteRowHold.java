package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;

/**
 * Every writer that changes an invite takes its row here first, so a racing revoke or accept either
 * waits or is seen: nobody writes over a status they did not check (ORISO-Admin#1026).
 */
final class InviteRowHold {

  private InviteRowHold() {}

  /** Holds the row until commit; 409 INVITE_NOT_PENDING if its status moved since it was read. */
  static void hold(AccountInviteRepository repository, AccountInvite invite, LocalDateTime now) {
    if (repository.holdInStatus(invite.getId(), invite.getStatus(), now) != 1) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.INVITE_NOT_PENDING, HttpStatus.CONFLICT);
    }
  }
}
