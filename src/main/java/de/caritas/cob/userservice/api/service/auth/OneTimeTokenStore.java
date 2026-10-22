package de.caritas.cob.userservice.api.service.auth;

import java.time.Instant;
import java.util.Optional;

/** Shared, fail-closed storage for short-lived, single-use authentication tokens. */
public interface OneTimeTokenStore {

  void store(
      String scope, String token, String subjectId, Instant expiresAt, boolean singlePerSubject);

  Optional<TokenClaim> claim(String scope, String token);

  boolean restore(String scope, String token, TokenClaim claim, boolean singlePerSubject);

  void discard(String scope, String token, String subjectId);

  /** Data-only issuance. Only the actual reset service turns an atomic claim into a live handle. */
  default void storePasswordReset(String token, PasswordResetBinding binding) {
    throw new IllegalStateException("Causal password-reset storage unavailable");
  }

  default void passwordResetDelivery(PasswordResetBinding binding, String delivery) {
    throw new IllegalStateException("Causal password-reset storage unavailable");
  }

  default Optional<PasswordResetClaim> claimPasswordReset(String token, String requestDigest) {
    throw new IllegalStateException("Causal password-reset storage unavailable");
  }

  default void requirePasswordResetClaim(PasswordResetClaim claim) {
    throw new IllegalStateException("Causal password-reset storage unavailable");
  }

  default void finishPasswordResetClaim(PasswordResetClaim claim, boolean accepted) {
    throw new IllegalStateException("Causal password-reset storage unavailable");
  }

  record PasswordResetClaim(
      PasswordResetBinding binding,
      java.util.UUID claimId,
      long version,
      String requestDigest,
      String status,
      String delivery) {
    @Override
    public String toString() {
      return "PasswordResetClaim[redacted]";
    }
  }

  record TokenClaim(String subjectId, Instant expiresAt) {}
}
