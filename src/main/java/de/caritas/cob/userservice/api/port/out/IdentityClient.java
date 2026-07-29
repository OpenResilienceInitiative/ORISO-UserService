package de.caritas.cob.userservice.api.port.out;

import java.util.List;
import org.keycloak.representations.idm.UserRepresentation;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  boolean userHasAuthority(String userId, String authority);

  boolean userHasRole(String userId, String userRole);

  List<UserRepresentation> findByUsername(String username);
}
