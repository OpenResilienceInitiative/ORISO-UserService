package de.caritas.cob.userservice.api.adapters.matrix;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * Turns a Matrix identifier into a short, stable, non-reversible token for use in logs and
 * exception messages. A localpart here is derived from a person's name or e-mail, so a log line
 * naming one names a counsellor or an advice seeker.
 *
 * <p>Keyed HMAC-SHA256 rather than a plain digest, like {@code ConsultantIdentityHasher}:
 * localparts are guessable, so an unkeyed hash would let anyone holding the log confirm a candidate
 * name by hashing it. The secret is optional here — unset means a per-process random key, so
 * redaction can never stop the service from starting.
 *
 * <p>Inputs are normalised, so a bare localpart and a full {@code @local:server} id yield the same
 * token and one person stays correlatable across call sites.
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

  /**
   * A Matrix user id embedded in free text — a URL path, a Synapse error message, a {@code
   * RestTemplate} exception message — raw ({@code @anna:server}) or percent-encoded ({@code
   * %40anna%3Aserver}).
   */
  private static final Pattern MATRIX_USER_ID =
      Pattern.compile("(?:@|%40)[^\\s/?#\"']+?(?::|%3A|%3a)[A-Za-z0-9.\\-]+(?::\\d+)?");

  /**
   * The HMAC key. Optional — unset means a per-process random key.
   *
   * <p>A configured production value must be independently generated with at least 256 bits of
   * entropy ({@code openssl rand -hex 32}) and never derived from a hostname, a deployment name or
   * another secret here: a guessable key restores the offline confirmation this exists to prevent.
   * No length check, because no check can measure entropy.
   */
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
    return pseudonymUnsynchronized(identifier);
  }

  /** Callers must already hold this instance's monitor; {@link Mac} is not thread safe. */
  private String pseudonymUnsynchronized(String identifier) {
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
   * The same text with every Matrix user id in it replaced by its pseudonym. Needed because free
   * text this class did not compose — a Synapse error body, a {@code RestTemplate} message, a URL
   * path — can carry the id next to the pseudonym and hand back what it withheld.
   *
   * @param text arbitrary free text, may be {@code null}
   * @return the text with Matrix user ids replaced, or {@code "null"} for {@code null}
   */
  public synchronized String scrub(String text) {
    if (text == null) {
      return "null";
    }
    return MATRIX_USER_ID
        .matcher(text)
        .replaceAll(
            match ->
                Matcher.quoteReplacement(pseudonymUnsynchronized(decodeQuietly(match.group()))));
  }

  /**
   * Percent-decoding that leaves {@code +} alone and degrades to the raw value rather than throwing
   * inside a log call. {@link UriUtils} and not {@code java.net.URLDecoder}: the latter turns
   * {@code +} into a space, which would give one person two pseudonyms.
   */
  private static String decodeQuietly(String value) {
    try {
      return UriUtils.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      return value;
    }
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
