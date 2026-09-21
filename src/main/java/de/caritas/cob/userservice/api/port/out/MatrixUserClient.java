package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;

/** Outbound Matrix account operations shared by identity and administration modules. */
public interface MatrixUserClient {

  String createUserId(String username, String password, String displayName)
      throws MatrixCreateUserException;

  /**
   * Mints a chat account, refusing rather than reactivating one the homeserver already holds. For
   * callers that repair an existing consultant: {@link #createUserId} answers a taken localpart by
   * reactivating the account behind it, and a localpart is only unique at a point in time, so that
   * account may belong to somebody else.
   *
   * @param username the localpart to mint
   * @param password the initial password
   * @param displayName the chat display name
   * @return the full Matrix user id of the account just minted
   * @throws MatrixCreateUserException when the homeserver refuses, the localpart included
   */
  String createUserIdWithoutReactivation(String username, String password, String displayName)
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
