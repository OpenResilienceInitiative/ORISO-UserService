package de.caritas.cob.userservice.api.service.matrixrtc;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MatrixRtcPolicyTokenVerifier {

  private final byte[] configuredToken;

  public MatrixRtcPolicyTokenVerifier(@Value("${matrixrtc.call.policy.token:}") String token) {
    configuredToken = token == null || token.isBlank() ? new byte[0] : token.getBytes(UTF_8);
  }

  public boolean isValid(String candidate) {
    return configuredToken.length > 0
        && candidate != null
        && MessageDigest.isEqual(configuredToken, candidate.getBytes(UTF_8));
  }
}
