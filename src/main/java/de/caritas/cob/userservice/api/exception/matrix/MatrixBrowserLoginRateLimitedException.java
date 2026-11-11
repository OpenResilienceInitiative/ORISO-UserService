package de.caritas.cob.userservice.api.exception.matrix;

import java.util.OptionalInt;
import lombok.Getter;

/**
 * Synapse throttled a browser device login. Carries only a validated retry delay, when available.
 */
@Getter
public class MatrixBrowserLoginRateLimitedException extends RuntimeException {

  private final OptionalInt retryAfterSeconds;

  public MatrixBrowserLoginRateLimitedException(OptionalInt retryAfterSeconds) {
    super("Matrix browser device login was rate limited");
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
