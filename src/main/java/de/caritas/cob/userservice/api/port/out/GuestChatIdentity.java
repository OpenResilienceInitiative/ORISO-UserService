package de.caritas.cob.userservice.api.port.out;

/** Create-only chat identity operations for durable guest Join. */
public interface GuestChatIdentity {
  /**
   * Recovers by uncached credential proof or creates the same bound identity without reactivation.
   */
  String ensureOwned(String username, String password);

  /**
   * Creates the exact identity, or reports a confirmed conflict or unknown outcome. Never
   * reactivates an existing identity or changes its credentials.
   */
  String createOnly(String username, String password);

  /**
   * Uncached credential proof for a durably bound candidate. False means rejected credentials,
   * never confirmed absence. Unknown provider or cleanup outcomes must fail.
   */
  boolean verifyOwnership(String username, String password);
}
