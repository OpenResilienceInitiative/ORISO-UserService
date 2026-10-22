package de.caritas.cob.userservice.api.port.out;

/** Updates an identity password without exposing provider-specific credential types. */
public interface IdentityPasswordUpdater {

  /** Checks adapter support before a workflow consumes its single-use invitation or reset claim. */
  default void requirePasswordUpdateSupport() {
    throw new ScopedIdentityCommandRequiredException();
  }

  void updatePassword(String userId, String password);

  /** Sets an administrator-chosen password that must be replaced at first sign-in. */
  void updateTemporaryPassword(String userId, String password);
}
