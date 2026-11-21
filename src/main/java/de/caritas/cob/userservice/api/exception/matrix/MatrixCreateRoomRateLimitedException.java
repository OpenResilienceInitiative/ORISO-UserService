package de.caritas.cob.userservice.api.exception.matrix;

import lombok.Getter;

/**
 * Synapse refused a room creation with {@code 429 M_LIMIT_EXCEEDED} (ORISO-UserService#1737).
 * Carries the server's own {@code retry_after_ms}, so the caller can try again instead of leaving
 * the room missing. A {@link MatrixCreateRoomException}, so existing callers are unchanged.
 */
@Getter
public class MatrixCreateRoomRateLimitedException extends MatrixCreateRoomException {

  private final long retryAfterMs;

  public MatrixCreateRoomRateLimitedException(String message, long retryAfterMs) {
    super(message);
    this.retryAfterMs = retryAfterMs;
  }
}
