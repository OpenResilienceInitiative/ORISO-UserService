package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** The call of a share link has ended (#1487). Mapped to 410 {"reason":"CALL_SHARE_LINK_ENDED"}. */
public class CallShareLinkEndedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String REASON = "CALL_SHARE_LINK_ENDED";

  public CallShareLinkEndedException() {
    super("The call of this link has ended");
  }
}
