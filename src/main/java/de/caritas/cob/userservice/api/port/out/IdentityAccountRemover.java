package de.caritas.cob.userservice.api.port.out;

/** Removes identities with explicit strict-deletion and best-effort rollback semantics. */
public interface IdentityAccountRemover {

  void deleteUser(String userId);

  void rollbackUser(String userId);

  /** Explicit tracked diagnostics; unsupported adapters fail closed. */
  default void deleteUserTracked(String userId) {
    throw new IllegalStateException("Tracked native removal is not implemented");
  }
}
