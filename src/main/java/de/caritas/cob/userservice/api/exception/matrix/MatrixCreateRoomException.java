package de.caritas.cob.userservice.api.exception.matrix;

/** Exception thrown when Matrix room creation fails. */
public class MatrixCreateRoomException extends Exception {

  public MatrixCreateRoomException(String message) {
    super(message);
  }

  public MatrixCreateRoomException(String message, Throwable cause) {
    super(message, cause);
  }
}
