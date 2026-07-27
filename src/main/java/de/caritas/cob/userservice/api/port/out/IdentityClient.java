package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;

public interface IdentityClient {

  String createKeycloakUser(final UserDTO user);

  String createKeycloakUser(final UserDTO user, final String firstName, final String lastName);

  void removeRoleIfPresent(final String userId, final String roleName);

  void updatePassword(final String userId, final String password);

  String updateDummyEmail(final String userId, UserDTO user);

  void updateDummyEmail(String userId);

  void updateUserData(final String userId, UserDTO userDTO, String firstName, String lastName);

  void rollBackUser(String userId);

  void deleteUser(String userId);

  void deactivateUser(String userId);
}
