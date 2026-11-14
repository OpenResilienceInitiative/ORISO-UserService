package de.caritas.cob.userservice.api.service.agencyinvitelink;

import org.springframework.http.HttpStatus;

/**
 * A direct invitation refuses the person (ORISO-Frontend#2252), with a reason the entry page can
 * show: the access mode excludes their kind of account (403), or the 1:1 the invitation was created
 * from ever carried unencrypted messages (409).
 */
public class DirectInvitationRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    DIRECT_INVITATION_GUESTS_ONLY(HttpStatus.FORBIDDEN),
    DIRECT_INVITATION_ACCOUNTS_ONLY(HttpStatus.FORBIDDEN),
    /**
     * The 1:1 the invitation came from ever carried an unencrypted message (or is not encrypted):
     * the server would still serve that plaintext to the newcomer, so nobody is let in.
     */
    DIRECT_INVITATION_HISTORY_NOT_ENCRYPTED(HttpStatus.CONFLICT);

    private final HttpStatus status;

    Reason(HttpStatus status) {
      this.status = status;
    }

    public HttpStatus status() {
      return status;
    }
  }

  private final Reason reason;

  public DirectInvitationRefusedException(Reason reason) {
    super("Direct invitation refused: " + reason.name());
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }

  /** The refusal for a person the access mode does not let in. */
  public static DirectInvitationRefusedException forAccessMode(DirectInvitationAccessMode mode) {
    return new DirectInvitationRefusedException(
        mode == DirectInvitationAccessMode.GUESTS_ONLY
            ? Reason.DIRECT_INVITATION_GUESTS_ONLY
            : Reason.DIRECT_INVITATION_ACCOUNTS_ONLY);
  }
}
