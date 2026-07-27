package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;

public interface IdentityClient {

  String createKeycloakUser(final UserDTO user);

  String createKeycloakUser(final UserDTO user, final String firstName, final String lastName);

  String updateDummyEmail(final String userId, UserDTO user);

  void updateDummyEmail(String userId);

  void rollBackUser(String userId);

  void deleteUser(String userId);
}
