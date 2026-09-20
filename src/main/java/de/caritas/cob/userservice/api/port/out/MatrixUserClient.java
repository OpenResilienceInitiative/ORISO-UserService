package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;

/** Outbound Matrix account operations shared by identity and administration modules. */
public interface MatrixUserClient {

  String createUserId(String username, String password, String displayName)
      throws MatrixCreateUserException;

  /**
   * Returns true for an existing account and false only for confirmed absence. Implementations must
   * fail when availability cannot be determined.
   */
  boolean userExistsStrict(String username);

  boolean updateUserDisplayName(String matrixUserId, String displayName);
}
