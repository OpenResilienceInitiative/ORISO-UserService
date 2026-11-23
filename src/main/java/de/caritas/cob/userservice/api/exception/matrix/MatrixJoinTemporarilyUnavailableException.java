package de.caritas.cob.userservice.api.exception.matrix;

/** Matrix did not provide a definitive answer for the join attempt. */
public class MatrixJoinTemporarilyUnavailableException extends Exception {

  public MatrixJoinTemporarilyUnavailableException() {
    super("Matrix room join is temporarily unavailable");
  }
}
