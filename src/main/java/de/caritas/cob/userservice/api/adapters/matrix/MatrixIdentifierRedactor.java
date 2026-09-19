package de.caritas.cob.userservice.api.adapters.matrix;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a Matrix identifier into a short, stable, non-reversible token for use in logs and
 * exception messages.
 *
 * <p>Matrix localparts on this platform are derived from a person's name or e-mail, so a log line
 * naming one names a counsellor or an advice seeker. Those lines leave the pod: aggregation,
 * retention, backups. The operator need, however, is only correlation — "which lines belong to the
 * same failed provisioning" — and a pseudonym serves that as well as the name does.
 *
 * <p>Deliberately HMAC-SHA256 rather than a plain digest, mirroring {@code ConsultantIdentityHasher}
 * and {@code MatrixRtcCorrelationIdHasher}: localparts are low-entropy and guessable, so an unkeyed
 * hash would let anyone holding the log confirm a candidate name simply by hashing it. Keying the
 * hash closes that confirmation channel.
 *
 * <p>Unlike those two, the secret here is optional. Their outputs are persisted or compared across
 * processes; this one is only ever read by a human scanning one log stream. When no secret is
 * configured a random per-process key is used, so redaction can never be the reason the service
 * refuses to start — logging degrades, it does not throw.
 *
 * <p>Inputs are normalised, so the same person yields the same token whether a call site holds the
 * bare localpart ({@code anna.beispiel}) or the full Matrix user id ({@code
 * @anna.beispiel:matrix.example.com}). Without that, the create path and the deactivate path could
 * not be correlated at all.
 */
@Slf4j
@Component
public class MatrixIdentifierRedactor {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int TOKEN_HEX_CHARS = 12;
  private static final String PREFIX = "mx#";

  /**
   * Token for a {@code null} identifier; distinguishable from a blank one, and from a real user.
   */
  static final String NULL_TOKEN = PREFIX + "null";

  /** Token for a blank identifier. */
  static final String BLANK_TOKEN = PREFIX + "blank";

  @Value("${matrix.log-pseudonym.hmac-secret:}")
  private String secret;

  private Mac mac;

  @PostConstruct
  void init() throws NoSuchAlgorithmException, InvalidKeyException {
    byte[] key;
    if (secret == null || secret.isBlank()) {
      key = new byte[32];
      new SecureRandom().nextBytes(key);
      log.info(
          "matrix.log-pseudonym.hmac-secret is not set; Matrix log pseudonyms use a per-process "
              + "key and therefore do not correlate across restarts or pods");
    } else {
      key = secret.getBytes(StandardCharsets.UTF_8);
    }
    mac = Mac.getInstance(ALGORITHM);
    mac.init(new SecretKeySpec(key, ALGORITHM));
  }

  /**
   * A ready-to-use instance for callers that live outside the Spring context. Package-private: the
   * application always gets the bean.
   *
   * @param secret the HMAC key, or {@code null}/blank for a random per-process key
   */
  static MatrixIdentifierRedactor withKey(String secret) {
    var redactor = new MatrixIdentifierRedactor();
    redactor.secret = secret;
    try {
      redactor.init();
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new IllegalStateException("HmacSHA256 unavailable", ex);
    }
    return redactor;
  }

  /**
   * The log token for a Matrix username, localpart or full Matrix user id.
   *
   * @param identifier a localpart, a username or a full {@code @local:server} Matrix user id
   * @return a short non-reversible token such as {@code mx#3f9a1c2b7d04}, never the input
   */
  public synchronized String pseudonym(String identifier) {
    if (identifier == null) {
      return NULL_TOKEN;
    }
    var normalised = normalise(identifier);
    if (normalised.isEmpty()) {
      return BLANK_TOKEN;
    }
    var hex = Hex.encodeHexString(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
    return PREFIX + hex.substring(0, TOKEN_HEX_CHARS);
  }

  /**
   * Reduces a Matrix identifier to its localpart, lowercased — the form Synapse itself stores — so
   * that {@code anna.beispiel} and {@code @anna.beispiel:matrix.example.com} share one token.
   */
  private static String normalise(String identifier) {
    var value = identifier.trim();
    if (value.startsWith("@")) {
      value = value.substring(1);
    }
    int serverSeparator = value.indexOf(':');
    if (serverSeparator >= 0) {
      value = value.substring(0, serverSeparator);
    }
    return value.toLowerCase(Locale.ROOT);
  }
}
