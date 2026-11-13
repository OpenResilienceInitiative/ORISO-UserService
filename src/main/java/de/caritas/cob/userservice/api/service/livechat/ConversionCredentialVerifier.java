package de.caritas.cob.userservice.api.service.livechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Salted verifier for a pending intent; prehash preserves bytes beyond BCrypt's 72-byte limit.
 * Pending intents have no expiry and retain this verifier until conversion succeeds.
 */
final class ConversionCredentialVerifier {
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

  private ConversionCredentialVerifier() {}

  static String encode(String password) {
    return ENCODER.encode(digest(password));
  }

  static boolean matches(String password, String verifier) {
    return verifier != null && ENCODER.matches(digest(password), verifier);
  }

  private static String digest(String password) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(password.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
