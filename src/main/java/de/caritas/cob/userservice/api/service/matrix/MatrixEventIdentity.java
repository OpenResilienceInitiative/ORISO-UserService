package de.caritas.cob.userservice.api.service.matrix;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * Produces stable opaque identifiers for durable Matrix event side effects.
 *
 * <p>Matrix event IDs are high-entropy transport identifiers. Persisting only their SHA-256 digest
 * prevents the database from becoming a second source of raw Matrix identifiers while retaining a
 * deterministic cross-replica idempotency key.
 */
public final class MatrixEventIdentity {

  private static final String DEDUPLICATION_KEY_PREFIX = "matrix-event:";

  private MatrixEventIdentity() {}

  public static String opaqueHash(String eventId) {
    if (eventId == null || eventId.isBlank()) {
      return null;
    }
    return DigestUtils.sha256Hex(eventId);
  }

  public static String deduplicationKey(String eventId) {
    String hash = opaqueHash(eventId);
    return hash == null ? null : DEDUPLICATION_KEY_PREFIX + hash;
  }
}
