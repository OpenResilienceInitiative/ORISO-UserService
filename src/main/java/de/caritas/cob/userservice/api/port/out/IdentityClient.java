package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  String createKeycloakUser(final UserDTO user);

  String createKeycloakUser(final UserDTO user, final String firstName, final String lastName);

  void updateUserRole(final String userId);

  void ensureRole(final String userId, final String roleName);

  void updateRole(final String userId, final UserRole role);

  void removeRoleIfPresent(final String userId, final String roleName);

  void updateRole(final String userId, final String roleName);

  void updatePassword(final String userId, final String password);

  String updateDummyEmail(final String userId, UserDTO user);

  void updateDummyEmail(String userId);

  void updateUserData(final String userId, UserDTO userDTO, String firstName, String lastName);

  void rollBackUser(String userId);

  void deleteUser(String userId);

  void closeSession(String sessionId);

  void deactivateUser(String userId);
}
