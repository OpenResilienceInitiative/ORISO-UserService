package de.caritas.cob.userservice.api.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * H2 aliases for the MariaDB advisory-lock functions used by integration tests.
 *
 * <p>The required E2E suite is single-instance and verifies the HTTP/JPA round trip. Cross-replica
 * lock behavior remains covered by {@code TutorialProgressServiceMariaDbReplicaIT} against real
 * MariaDB.
 */
public final class H2MariaDbAdvisoryLockFunctions {

  private H2MariaDbAdvisoryLockFunctions() {}

  public static String sha2(String value, int digestLength) {
    if (digestLength != 256) {
      throw new IllegalArgumentException("test SHA2 alias supports only SHA-256");
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static int getLock(String lockName, int timeoutSeconds) {
    return 1;
  }

  public static int releaseLock(String lockName) {
    return 1;
  }
}
