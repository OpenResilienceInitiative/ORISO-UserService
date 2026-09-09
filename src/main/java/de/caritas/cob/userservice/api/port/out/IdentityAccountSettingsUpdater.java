package de.caritas.cob.userservice.api.port.out;

/**
 * Focused outbound contract for changes a user makes to their own account settings.
 *
 * <p>Separate from the broad {@code IdentityClient}: provisioning and administrative password
 * resets are a different concern with a different blast radius, and keeping self-service settings
 * here means a caller cannot reach the provisioning surface just to change a language.
 */
public interface IdentityAccountSettingsUpdater {

  boolean changePassword(String userId, String password);

  void changePreferredLanguage(String userId, String language);
}
