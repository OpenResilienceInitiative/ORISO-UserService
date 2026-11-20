package de.caritas.cob.userservice.api.service.calendar;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs the address of a subscription feed (ADR-032, ORISO-UserService#1645). The address carries
 * the stored feed id and {@code base64url(HMAC-SHA256(key, feedId))} cut to 22 characters (132
 * bits), so a copy of the database alone forges no address. The key is derived from the configured
 * secret with the label {@value #LABEL}: the secret may be shared with another purpose without the
 * two ever producing the same value.
 */
@Component
public class CalendarFeedSigner {

  static final String LABEL = "oriso-calendar-feed-v1";
  static final int SIGNATURE_LENGTH = 22;
  private static final String ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  public CalendarFeedSigner(@Value("${calendar.feed.secret:}") String secret) {
    if (secret == null || secret.isBlank()) {
      // Optional variable: by default the statistics secret, which has its own startup guard,
      // stands in (application.properties), so this only fires on an explicitly blank value.
      throw new IllegalStateException(
          "calendar.feed.secret is blank: set CALENDAR_FEED_SECRET or leave it unset so that"
              + " STATISTICS_MESSAGE_COUNT_HMAC_SECRET is used");
    }
    this.key =
        new SecretKeySpec(
            hmac(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM),
                LABEL.getBytes(StandardCharsets.UTF_8)),
            ALGORITHM);
  }

  public String sign(UUID feedId) {
    var mac = hmac(key, feedId.toString().getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac)
        .substring(0, SIGNATURE_LENGTH);
  }

  /** Constant-time comparison; a missing signature never matches. */
  public boolean matches(UUID feedId, String presented) {
    if (feedId == null || presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(feedId).getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] hmac(SecretKeySpec with, byte[] data) {
    try {
      var mac = Mac.getInstance(ALGORITHM);
      mac.init(with);
      return mac.doFinal(data);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HMAC-SHA256 is not available", impossible);
    }
  }
}
