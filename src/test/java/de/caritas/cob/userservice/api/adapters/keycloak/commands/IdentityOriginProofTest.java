package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class IdentityOriginProofTest {
  private static final String KEY =
      Base64.getEncoder()
          .encodeToString(
              "test-provisioning-key-32-bytes-minimum"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  private final IdentityOriginProof issuer =
      new IdentityOriginProof(
          KEY,
          Base64.getEncoder()
              .encodeToString(
                  "test-maintenance-key-32-bytes-minimum"
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC));

  @Test
  void selfServiceCannotMintAuthorizationForAnotherAccount() {
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.selfService(
                    caller(), "foreign-account", "account.password"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void selfServiceCannotMintRoleAdministrationAuthorization() {
    assertThatThrownBy(
            () ->
                IdentityCommandAuthorization.selfService(caller(), "own-account", "account.roles"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void proofCryptographicallyBindsActorOperationTargetAndCompletePayload() throws Exception {
    var config = new de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration();
    config
        .getTasks()
        .put(
            "account-maintenance",
            new de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials(
                "backend-account-maintenance", "irrelevant-client-secret", "maintenance-subject"));
    var identity =
        config.require(de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_MAINTENANCE);
    String encoded =
        issuer.issue(
            identity,
            IdentityCommandAuthorization.selfService(caller(), "own-account", "account.password"),
            Map.of("password", "test-only-value", "passwordTemporary", false));
    var proof = SignedJWT.parse(encoded);
    assertThat(proof.verify(new MACVerifier("test-maintenance-key-32-bytes-minimum"))).isTrue();
    var claims = proof.getJWTClaimsSet();
    assertThat(claims.getStringClaim("target")).isEqualTo("own-account");
    assertThat(claims.getStringClaim("taskSubject")).isEqualTo("maintenance-subject");
    assertThat(claims.getStringClaim("operation")).isEqualTo("account.password");
    assertThat(claims.getExpirationTime().toInstant())
        .isEqualTo(Instant.parse("2026-10-07T12:01:00Z"));
    assertThat(claims.getStringClaim("payloadDigest")).isNotBlank();
    assertThat(encoded).doesNotContain("test-only-value");
  }

  @Test
  void provisionerReadUsesItsOwnProvisioningKeyRatherThanTheMaintenanceOperationDefault()
      throws Exception {
    var config = new de.caritas.cob.userservice.api.config.auth.TaskIdentityConfiguration();
    config
        .getTasks()
        .put(
            "account-provisioning",
            new de.caritas.cob.userservice.api.config.auth.TaskIdentityCredentials(
                "backend-account-provisioning", "unused", "creator-subject"));
    var identity =
        config.require(
            de.caritas.cob.userservice.api.config.auth.TaskIdentity.ACCOUNT_PROVISIONING);
    var auth =
        new IdentityCommandAuthorization(
            "REGISTRATION", "account.search", "exact-name", "7", java.util.List.of("user"));
    var signed = SignedJWT.parse(issuer.issue(identity, auth, Map.of("username", "exact-name")));
    assertThat(signed.verify(new MACVerifier(Base64.getDecoder().decode(KEY)))).isTrue();
    assertThat(signed.verify(new MACVerifier("test-maintenance-key-32-bytes-minimum"))).isFalse();
  }

  private Jwt caller() {
    return Jwt.withTokenValue("verified-human-token")
        .header("alg", "RS256")
        .subject("own-account")
        .claim("tenantId", "7")
        .build();
  }
}
