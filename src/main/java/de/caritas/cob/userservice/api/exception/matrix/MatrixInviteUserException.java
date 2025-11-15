package de.caritas.cob.userservice.api.exception.matrix;

/** Exception thrown when Matrix invite user fails. */
public class MatrixInviteUserException extends Exception {

  public MatrixInviteUserException(String message) {
    super(message);
  }

  public MatrixInviteUserException(String message, Throwable cause) {
    super(message, cause);
  }
}
