package de.caritas.cob.userservice.api.port.out;

/**
 * Withdraws every support access a Global Support Admin currently holds (ADR-018). Disabling a GSA
 * must not wait for the four-hour lease, so the admin lifecycle drives revocation through this port
 * instead of depending on the session service directly.
 */
public interface SupportAccessRevoker {

  /**
   * Marks all non-terminal sessions of that support admin for revocation. Returns how many were
   * marked. The actual Matrix withdrawal happens asynchronously and is only reported as closed once
   * the homeserver confirms it.
   */
  int revokeAllForSupportAdmin(String supportAdminId, String reason);
}
