package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;

/** An indeterminate identity read cannot release or promise an invitation recipient address. */
public final class InvitationRecipientAvailabilityUnavailableException
    extends ServiceUnavailableException {
  public InvitationRecipientAvailabilityUnavailableException() {
    super("Invitation recipient availability could not be confirmed");
  }
}
