package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;

/** Outbound Matrix account operations shared by identity and administration modules. */
public interface MatrixUserClient {

  String createUserId(String username, String password, String displayName)
      throws MatrixCreateUserException;

  boolean updateUserDisplayName(String matrixUserId, String displayName);

  /**
   * The Matrix user id already held by the given localpart, if the homeserver has one.
   *
   * <p>Needed for reconciliation (#1194): a repair that created the account and then failed to
   * commit the id leaves an orphan behind, and creation alone can never finish that job, because
   * the homeserver refuses to mint the same user twice. Resolving the existing id lets the next
   * attempt adopt it instead.
   *
   * @param localpart the part before {@code :server}
   * @return the full Matrix user id, or {@code null} when the homeserver has no such user or its
   *     existence could not be established
   */
  String findUserId(String localpart);
}
