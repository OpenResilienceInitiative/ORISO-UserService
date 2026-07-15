package de.caritas.cob.userservice.api.service.statistics;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pseudonymizes a consultant id for storage in message-count statistics, so that nobody with direct
 * database access (backup, read replica, another service) can link a row to a specific consultant
 * without the secret held here. This is deliberately deterministic pseudonymization (HMAC-SHA256),
 * not encryption: the statistics service needs to re-derive the same hash for the authenticated
 * caller's own id on every read, and agency-level aggregation only needs the hash to be stable, not
 * reversible.
 */
@Component
public class ConsultantIdentityHasher {

  private static final String ALGORITHM = "HmacSHA256";

  @Value("${statistics.message-count.hmac-secret}")
  private String secret;

  private Mac mac;

  @PostConstruct
  void init() throws NoSuchAlgorithmException, InvalidKeyException {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "statistics.message-count.hmac-secret must be set (STATISTICS_MESSAGE_COUNT_HMAC_SECRET)");
    }
    mac = Mac.getInstance(ALGORITHM);
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
  }

  /** Returns the hex-encoded HMAC-SHA256 of the given consultant id. */
  public synchronized String hash(String consultantId) {
    return Hex.encodeHexString(mac.doFinal(consultantId.getBytes(StandardCharsets.UTF_8)));
  }
}
