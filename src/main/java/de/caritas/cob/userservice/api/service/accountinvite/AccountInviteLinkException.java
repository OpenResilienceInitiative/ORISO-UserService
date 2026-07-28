package de.caritas.cob.userservice.api.service.accountinvite;

/**
 * An invite link exists but is no longer usable (TEN-INV-U6, #890). Carries a distinct,
 * machine-readable reason so public frontends can render dedicated terminal states; mapped to 410
 * Gone with body {@code {"reason": "<REASON>"}}. Unknown tokens stay 404.
 */
public class AccountInviteLinkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the link cannot (or can no longer) be used. */
  public enum Reason {
    /** The invite was already accepted — links are strictly single-use. */
    CONSUMED,
    /** An admin revoked the invite. */
    REVOKED,
    /** The invite was replaced by a resend; only the newest link is valid. */
    SUPERSEDED,
    /** The invite passed its expiry date. */
    EXPIRED,
    /** The invite is not in a deliverable state (e.g. draft that was never sent). */
    NOT_ACTIVE
  }

  private final Reason reason;

  public AccountInviteLinkException(Reason reason) {
    super("Account invite link is not usable: " + reason);
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }
}
