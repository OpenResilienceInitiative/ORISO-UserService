package de.caritas.cob.userservice.api.service.auth;

import java.time.Instant;
import java.util.UUID;

/** Immutable original issuance facts; this public data is never provider authority. */
public record PasswordResetBinding(
    UUID workflowId,
    String subjectId,
    Long tenantId,
    String username,
    String accountKind,
    String accountVersion,
    String recipientDigest,
    Instant expiresAt) {
  @Override
  public String toString() {
    return "PasswordResetBinding[redacted]";
  }
}
