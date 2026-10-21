package de.caritas.cob.userservice.api.port.out;

/** A target-only legacy call cannot authorize a five-actor identity operation. */
public final class ScopedIdentityCommandRequiredException extends IllegalStateException {
  public ScopedIdentityCommandRequiredException() {
    super("Scoped account maintenance command required");
  }
}
