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

  record TokenClaim(String subjectId, Instant expiresAt) {}
}
