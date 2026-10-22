package de.caritas.cob.userservice.api.exception.matrix;

/** A definite homeserver registration rejection before creating or adopting any identity. */
public final class MatrixUserInUseException extends MatrixCreateUserException {
  public MatrixUserInUseException() {
    super("Matrix registration was rejected without creating an account");
  }
}
