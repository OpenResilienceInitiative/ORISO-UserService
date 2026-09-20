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

  /**
   * The Matrix user id already held by the given localpart, if the homeserver has a usable account
   * for it. The homeserver refuses to mint the same user twice, so a repair that created the
   * account and failed to store the id can only finish by adopting it.
   *
   * @param localpart the part before {@code :server}
   * @return the full Matrix user id, or {@code null} when there is no usable account
   */
  String findUserId(String localpart);
}
