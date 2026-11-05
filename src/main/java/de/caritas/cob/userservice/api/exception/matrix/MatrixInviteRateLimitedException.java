package de.caritas.cob.userservice.api.exception.matrix;

import lombok.Getter;

/**
 * Synapse rejected an invite with {@code 429 M_LIMIT_EXCEEDED}. Carries the server's own {@code
 * retry_after_ms} so the caller decides whether to wait for it or hand the work to the background.
 */
@Getter
public class MatrixInviteRateLimitedException extends MatrixInviteUserException {

  private final long retryAfterMs;

  public MatrixInviteRateLimitedException(String message, long retryAfterMs) {
    super(message);
    this.retryAfterMs = retryAfterMs;
  }
}
