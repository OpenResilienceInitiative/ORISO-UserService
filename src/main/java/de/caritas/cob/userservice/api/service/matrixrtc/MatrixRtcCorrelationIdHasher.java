package de.caritas.cob.userservice.api.service.matrixrtc;

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
 * Derives a keyed correlation value for a Matrix room id / user id pair, for use in {@link
 * MatrixRtcCallPolicyService} denial logs.
 *
 * <p>This is deliberately HMAC-SHA256, not a plain digest: an unkeyed hash of the pair would let
 * anyone with a candidate room id and user id confirm whether it matches a logged correlation value
 * simply by hashing it themselves. Keying the hash with a secret only this service holds closes
 * that confirmation channel. Mirrors {@code ConsultantIdentityHasher}'s pseudonymization pattern in
 * the statistics package.
 */
@Component
public class MatrixRtcCorrelationIdHasher {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int CORRELATION_ID_HEX_CHARS = 12;

  @Value("${matrixrtc.call-policy.hmac-secret}")
  private String secret;

  private Mac mac;

  @PostConstruct
  void init() throws NoSuchAlgorithmException, InvalidKeyException {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "matrixrtc.call-policy.hmac-secret must be set (MATRIXRTC_CALL_POLICY_HMAC_SECRET)");
    }
    mac = Mac.getInstance(ALGORITHM);
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
  }

  /** Returns the truncated, hex-encoded HMAC-SHA256 of the given room id / user id pair. */
  public synchronized String correlationId(String sourceRoomId, String matrixUserId) {
    var hex =
        Hex.encodeHexString(
            mac.doFinal((sourceRoomId + ':' + matrixUserId).getBytes(StandardCharsets.UTF_8)));
    return hex.substring(0, CORRELATION_ID_HEX_CHARS);
  }
}
