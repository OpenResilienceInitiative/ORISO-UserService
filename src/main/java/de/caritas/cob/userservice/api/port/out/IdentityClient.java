package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import java.util.Map;
import java.util.Optional;

public interface IdentityClient {

  boolean changePassword(final String userId, final String password);

  void changeLanguage(final String userId, final String language);

  void changeEmailAddress(final String emailAddress);

  void changeEmailAddress(final String username, final String emailAddress);

  void deleteEmailAddress();

  OtpInfoDTO getOtpCredential(final String userName);

  boolean setUpOtpCredential(final String userName, final String initialCode, final String secret);

  void deleteOtpCredential(final String userName);

  Optional<String> initiateEmailVerification(final String username, final String email);

  Map<String, String> finishEmailVerification(final String username, final String initialCode);

  CreatedIdentity createUser(final UserDTO user);

  CreatedIdentity createUser(final UserDTO user, final String firstName, final String lastName);

  void updateUserRole(final String userId);

  void ensureRole(final String userId, final String roleName);

  void updateRole(final String userId, final UserRole role);

  void removeRoleIfPresent(final String userId, final String roleName);

  void updateRole(final String userId, final String roleName);

  void updateUserData(final String userId, UserDTO userDTO, String firstName, String lastName);

  void updateEmail(String userId, String emailAddress);

  boolean userHasAuthority(String userId, String authority);

  boolean userHasRole(String userId, String userRole);
}
