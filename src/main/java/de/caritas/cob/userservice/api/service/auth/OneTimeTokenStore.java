package de.caritas.cob.userservice.api.service.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Shared, fail-closed storage for short-lived, single-use authentication tokens. */
public interface OneTimeTokenStore {

  void store(
      String scope, String token, String subjectId, Instant expiresAt, boolean singlePerSubject);

  Optional<TokenClaim> claim(String scope, String token);

  boolean restore(String scope, String token, TokenClaim claim, boolean singlePerSubject);

  void discard(String scope, String token, String subjectId);

  /** Scoped magic-link metadata is data; only a successful store claim creates live authority. */
  default void storeMagicLink(String token, TokenClaim claim) {
    throw new IllegalStateException("Scoped magic-link storage unavailable");
  }

  record TokenClaim(
      String subjectId,
      Instant expiresAt,
      UUID workflowId,
      Long tenantId,
      String capabilityDigest) {
    public TokenClaim(String subjectId, Instant expiresAt) {
      this(subjectId, expiresAt, null, null, null);
    }

    @Override
    public String toString() {
      return "TokenClaim[redacted]";
    }
  }
}
