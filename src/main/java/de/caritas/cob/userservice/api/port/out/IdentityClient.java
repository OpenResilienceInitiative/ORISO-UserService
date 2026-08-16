package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  CreatedIdentity createUser(final UserDTO user);

  CreatedIdentity createUser(final UserDTO user, final String firstName, final String lastName);

  void removeRoleIfPresent(final String userId, final String roleName);
}
