package de.caritas.cob.userservice.api.port.out;

/** Updates an identity password without exposing provider-specific credential types. */
public interface IdentityPasswordUpdater {

  /** Checks adapter support before a workflow consumes its single-use invitation or reset claim. */
  default void requirePasswordUpdateSupport() {
    throw new ScopedIdentityCommandRequiredException();
  }

  /** Selects the application-owned causal setup workflow; legacy adapters keep their old path. */
  default boolean requiresCausalPasswordClaims() {
    return false;
  }

  /** Pure preflight: an unsupported typed adapter must refuse before claiming the invitation. */
  default void requireExistingSetupPasswordSupport() {
    throw new ScopedIdentityCommandRequiredException();
  }

  /** Pure keyed binding supplied by the checked adapter; it must perform no remote operation. */
  default String existingSetupRequestDigest(
      ExistingSetupPasswordClaims.Binding binding, String chosenPassword) {
    throw new ScopedIdentityCommandRequiredException();
  }

  default void updateExistingSetupPassword(
      de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupService.ClaimedSetup
          claimedSetup,
      String password) {
    throw new ScopedIdentityCommandRequiredException();
  }

  default void requirePasswordResetSupport() {
    throw new ScopedIdentityCommandRequiredException();
  }

  /** Pure local issuance data only. It must not issue a bearer or perform a provider effect. */
  default de.caritas.cob.userservice.api.service.auth.PasswordResetBinding passwordResetIssuance(
      java.util.UUID workflowId, String accountId, Long tenantId, String username,
      String recipient, String accountKind, java.time.Instant expiresAt) {
    throw new ScopedIdentityCommandRequiredException();
  }

  default String passwordResetRequestDigest(
      de.caritas.cob.userservice.api.service.auth.PasswordResetBinding binding, String password) {
    throw new ScopedIdentityCommandRequiredException();
  }

  default void updateClaimedPasswordReset(
      de.caritas.cob.userservice.api.service.auth.PasswordResetService.ClaimedReset claimedReset,
      String password) {
    throw new ScopedIdentityCommandRequiredException();
  }

  void updatePassword(String userId, String password);

  /** Sets an administrator-chosen password that must be replaced at first sign-in. */
  void updateTemporaryPassword(String userId, String password);
}
