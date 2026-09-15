package de.caritas.cob.userservice.api.picture;

import org.slf4j.MDC;

/** Keeps private-picture diagnostic records free of ambient request data. */
public final class PictureDiagnostics {
  private PictureDiagnostics() {}

  /** Run only the diagnostic call here; restore the caller's context even if logging fails. */
  public static void withoutRequestContext(Runnable diagnostic) {
    var previous = MDC.getCopyOfContextMap();
    try {
      MDC.clear();
      diagnostic.run();
    } finally {
      if (previous == null) MDC.clear();
      else MDC.setContextMap(previous);
    }
  }
}
