package de.caritas.cob.userservice.api.service.agencyinvitelink;

/**
 * A direct invitation whose validity after the appointment ran out, or whose token was replaced by
 * "Neuen Link senden" (ORISO-Frontend#2252). Mapped to 410 with body
 * {"reason":"DIRECT_LINK_EXPIRED"}, so the entry page shows "Link abgelaufen" with "Neuen Link
 * anfragen".
 */
public class DirectLinkExpiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String REASON = "DIRECT_LINK_EXPIRED";

  public DirectLinkExpiredException() {
    super("Direct invitation expired");
  }
}
