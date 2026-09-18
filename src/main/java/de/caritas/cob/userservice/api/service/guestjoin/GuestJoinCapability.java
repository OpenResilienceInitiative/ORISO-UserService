package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request-scoped 256-bit retry capability. Only its hash may be persisted. The caller must generate
 * the key with a cryptographic RNG and send it in the request body, never the URL. Avoid record or
 * generated toString methods: they would expose the secret in diagnostics.
 */
public final class GuestJoinCapability {
  private final byte[] secret;

  private GuestJoinCapability(byte[] secret) {
    this.secret = secret;
  }

  public static GuestJoinCapability parse(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{43}")) throw invalid();
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(key);
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
    if (bytes.length != 32
        || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(key)) {
      throw invalid();
    }
    return new GuestJoinCapability(bytes);
  }

  public String attemptHash() {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Required guest Join digest unavailable");
    }
  }

  public String identityPassword(String username) {
    return password("identity", username);
  }

  public String chatPassword(String username) {
    return password("matrix", username);
  }

  private String password(String provider, String username) {
    if (username == null || !username.matches("[a-z0-9_]{3,30}")) {
      throw new BadRequestException("Invalid guest identity username");
    }
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] derived =
          mac.doFinal(
              ("oriso/guest-join/password/v1\0" + provider + "\0" + username)
                  .getBytes(StandardCharsets.UTF_8));
      // Fixed policy characters; entropy comes from the complete 256-bit derived value.
      return "Gj1!aA" + HexFormat.of().formatHex(derived);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Required guest Join derivation unavailable");
    }
  }

  private static BadRequestException invalid() {
    return new BadRequestException("Invalid guest Join retry key");
  }
}
