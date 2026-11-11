package de.caritas.cob.userservice.api.port.out;

/** Focused outbound contract for the tenant an identity provider puts into a user's tokens. */
public interface IdentityTenantUpdater {

  /**
   * Sets the tenant of the account. Every token issued afterwards carries it; nothing else of the
   * account (enabled flag, roles, other attributes) changes.
   */
  void updateTenant(String userId, Long tenantId);

  /**
   * Ends every login session of the account, so the previous tenant stops working as soon as the
   * access tokens issued for it have expired.
   */
  void endSessions(String userId);
}
