package de.caritas.cob.userservice.api.service.session;

import org.springframework.stereotype.Component;

/** The one place membership work sleeps for Synapse's {@code retry_after_ms}; a seam for tests. */
@Component
public class MatrixRateLimitPause {

  /**
   * @return {@code false} when the thread was interrupted, so the caller stops instead of looping
   */
  public boolean pause(long millis) {
    if (millis <= 0) {
      return true;
    }
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
