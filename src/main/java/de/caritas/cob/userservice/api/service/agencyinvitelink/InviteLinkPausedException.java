package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** The invitation is paused and cannot create a conversation until reactivated. */
public class InviteLinkPausedException extends RuntimeException {

  public static final String REASON = "INVITE_LINK_PAUSED";

  public InviteLinkPausedException() {
    super("Invite link is paused");
  }
}
