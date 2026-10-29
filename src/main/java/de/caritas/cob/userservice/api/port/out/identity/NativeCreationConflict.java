package de.caritas.cob.userservice.api.port.out.identity;

/** A known provider conflict, with no provider body, selector, cause or retry permission. */
public final class NativeCreationConflict extends RuntimeException {
  public NativeCreationConflict() {
    super("Native identity creation conflict", null, false, false);
  }
}
