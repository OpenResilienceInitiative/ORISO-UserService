package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import java.util.List;
import org.keycloak.representations.idm.UserRepresentation;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  KeycloakCreateUserResponseDTO createKeycloakUser(final UserDTO user);

  KeycloakCreateUserResponseDTO createKeycloakUser(
      final UserDTO user, final String firstName, final String lastName);

  void updateUserRole(final String userId);

  void updateRole(final String userId, final UserRole role);

  void removeRoleIfPresent(final String userId, final String roleName);

  void updateRole(final String userId, final String roleName);

  void updatePassword(final String userId, final String password);

  String updateDummyEmail(final String userId, UserDTO user);

  void updateDummyEmail(String userId);

  void rollBackUser(String userId);

  void deleteUser(String userId);

  boolean userHasAuthority(String userId, String authority);

  boolean userHasRole(String userId, String userRole);

  List<UserRepresentation> findByUsername(String username);

  void deactivateUser(String userId);
}
