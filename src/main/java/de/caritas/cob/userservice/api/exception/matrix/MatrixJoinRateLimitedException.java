package de.caritas.cob.userservice.api.exception.matrix;

import lombok.Getter;

/** Synapse rate-limited a join and supplied a delay that the caller can honor before retrying. */
@Getter
public class MatrixJoinRateLimitedException extends Exception {

  private final long retryAfterMs;

  public MatrixJoinRateLimitedException(String message, long retryAfterMs) {
    super(message);
    this.retryAfterMs = retryAfterMs;
  }
}
