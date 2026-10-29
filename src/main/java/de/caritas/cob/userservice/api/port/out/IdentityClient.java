package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.port.out.identity.NativeCreationCapture;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  CreatedIdentity createUser(final UserDTO user);

  CreatedIdentity createUser(final UserDTO user, final String firstName, final String lastName);

  /** Tracked callers require a provider implementation that captures before any later mutation. */
  default CreatedIdentity createUser(
      UserDTO user, String firstName, String lastName, NativeCreationCapture capture) {
    throw new UnsupportedOperationException("Native creation capture is not supported");
  }

  void updateUserRole(final String userId);

  void updateRole(final String userId, final UserRole role);

  void removeRoleIfPresent(final String userId, final String roleName);

  void updateRole(final String userId, final String roleName);
}
