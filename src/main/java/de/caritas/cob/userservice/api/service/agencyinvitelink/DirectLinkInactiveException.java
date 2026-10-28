package de.caritas.cob.userservice.api.service.agencyinvitelink;

/** The owner switched the link off. Mapped to 410 with body {"reason":"DIRECT_LINK_INACTIVE"}. */
public class DirectLinkInactiveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String REASON = "DIRECT_LINK_INACTIVE";

  public DirectLinkInactiveException() {
    super("Invite link is no longer active");
  }
}
