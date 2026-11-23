package de.caritas.cob.userservice.api.adapters.matrix;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stable account credential for device login and delayed browser interactive authentication. */
final class MatrixBrowserCredential {
  private static final String DOMAIN = "oriso.matrix.browser-uia:v1\0";

  private MatrixBrowserCredential() {}

  static String derive(String registrationSharedSecret, String matrixUserId) {
    if (registrationSharedSecret == null || registrationSharedSecret.isBlank()) {
      throw new IllegalArgumentException("Matrix registration shared secret is required");
    }
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              registrationSharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      var digest = mac.doFinal((DOMAIN + matrixUserId).getBytes(StandardCharsets.UTF_8));
      return "ORISO-v1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Cannot derive Matrix browser credential", ex);
    }
  }
}
