package de.caritas.cob.userservice.api.exception.matrix;

/** Exception thrown when Matrix user creation fails. */
public class MatrixCreateUserException extends Exception {

  public MatrixCreateUserException(String message) {
    super(message);
  }

  public MatrixCreateUserException(String message, Throwable cause) {
    super(message, cause);
  }
}
