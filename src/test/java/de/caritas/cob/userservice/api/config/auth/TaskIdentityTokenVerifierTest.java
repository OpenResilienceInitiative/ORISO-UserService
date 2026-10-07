package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class TaskIdentityTokenVerifierTest {
  private static final byte[] KEY =
      "test-only-key-with-at-least-32-characters".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private final TaskIdentityTokenVerifier verifier =
      new TaskIdentityTokenVerifier(
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build());
  private final TaskIdentityCredentials identity =
      new TaskIdentityCredentials(
          "backend-config-wizard", "unused-test-credential", "wizard-subject");

  {
    identity.bindTask(TaskIdentity.CONFIG_WIZARD);
  }

  @Test
  void tokenFromAnotherServiceAccountCannotBeUsedForWizardOperations() throws Exception {
    assertThatThrownBy(
            () -> verifier.verify(identity, signedToken("mail-subject", "backend-config-wizard")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void sameSubjectWithAnotherClientCannotBeUsedForWizardOperations() throws Exception {
    assertThatThrownBy(
            () -> verifier.verify(identity, signedToken("wizard-subject", "backend-mail")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void verifiedTaskTokenWithOwnClientAndSubjectIsAccepted() throws Exception {
    assertThatCode(
            () -> verifier.verify(identity, signedToken("wizard-subject", "backend-config-wizard")))
        .doesNotThrowAnyException();
  }

  private String signedToken(String subject, String client) throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("azp", client)
                .audience(
                    java.util.List.of("tenantservice", "agencyservice", "consultingtypeservice"))
                .claim(
                    "realm_access", java.util.Map.of("roles", java.util.List.of("config-wizard")))
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build());
    jwt.sign(new MACSigner(KEY));
    return jwt.serialize();
  }
}
