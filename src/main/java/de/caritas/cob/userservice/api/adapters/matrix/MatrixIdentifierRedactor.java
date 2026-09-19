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

  /**
   * A Matrix user id embedded in free text — a URL path, a Synapse error message, a {@code
   * RestTemplate} exception message — raw ({@code @anna:server}) or percent-encoded ({@code
   * %40anna%3Aserver}).
   */
  private static final Pattern MATRIX_USER_ID =
      Pattern.compile("(?:@|%40)[^\\s/?#\"']+?(?::|%3A|%3a)[A-Za-z0-9.\\-]+(?::\\d+)?");

  /**
   * The HMAC key. Optional on purpose — see the class javadoc.
   *
   * <p>A configured production value must be independently generated with at least 256 bits of
   * entropy (for example {@code openssl rand -hex 32}) and must not be derived from anything
   * guessable such as a hostname, a deployment name or another secret in this file. The key is the
   * only thing standing between a log archive and offline confirmation of a suspected name: given a
   * guessable key, anyone holding the logs can hash candidate localparts until one matches, which
   * is precisely the attack the keyed HMAC exists to prevent. No length or format check is made
   * here, because the contract accepts arbitrary UTF-8 and no check can measure entropy.
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
   * The same text with every Matrix user id in it replaced by its pseudonym.
   *
   * <p>Redacting only the arguments a call site controls is not enough: a Synapse error body, a
   * {@code RestTemplate} exception message and a URL path all carry the user id in free text, and
   * printing one next to a pseudonym in the same statement hands back exactly what the pseudonym
   * withheld. This is the safety net over everything this adapter did not compose itself.
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
   * inside a log call.
   *
   * <p>Deliberately {@link UriUtils} and not {@code java.net.URLDecoder}: the latter applies
   * form-encoding rules and turns {@code +} into a space, and a Matrix localpart may contain {@code
   * +}. That would hash {@code anna x} here and {@code anna+x} at every explicit call site — one
   * person, two pseudonyms, in the one place an operator goes looking for the correlation.
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
