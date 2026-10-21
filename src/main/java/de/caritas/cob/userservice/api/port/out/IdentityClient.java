package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;

public interface IdentityClient {

  CreatedIdentity createUser(final UserDTO user);

  CreatedIdentity createUser(final UserDTO user, final String firstName, final String lastName);

  void updateUserRole(final String userId);

  void updateRole(final String userId, final UserRole role);

  void removeRoleIfPresent(final String userId, final String roleName);

  void updateRole(final String userId, final String roleName);
}
