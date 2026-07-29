package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import java.util.List;
import org.keycloak.representations.idm.UserRepresentation;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  KeycloakCreateUserResponseDTO createKeycloakUser(final UserDTO user);

  KeycloakCreateUserResponseDTO createKeycloakUser(
      final UserDTO user, final String firstName, final String lastName);

  void removeRoleIfPresent(final String userId, final String roleName);

  boolean userHasAuthority(String userId, String authority);

  boolean userHasRole(String userId, String userRole);

  List<UserRepresentation> findByUsername(String username);
}
