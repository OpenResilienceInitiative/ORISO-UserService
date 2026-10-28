package de.caritas.cob.userservice.api.config.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only real signature + all-five configured native binding; no authority tuple constructor.
 */
public final class ConfiguredImportTriggerFixture {
  private ConfiguredImportTriggerFixture() {}

  public static TaskActorTokenBroker.VerifiedConsultantImportTrigger verified() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      String subject = "84000000-0000-4000-8000-000000000001";
      var environment =
          FiveActorConfigurationTest.environment()
              .withProperty("IDENTITY_SETUP_SERVICE_SUBJECT", subject);
      var config = new KeycloakConfig();
      config.setAuthServerUrl("https://public-auth.example.test");
      config.setRealm("synthetic");
      var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
      var broker =
          new TaskActorTokenBroker(
              FiveActorConfigurationTest.configured(environment),
              org.mockito.Mockito.mock(KeycloakAuthClient.class),
              decoder,
              config);
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("https://public-auth.example.test/realms/synthetic")
              .subject(subject)
              .claim("azp", "backend-setup")
              .audience(
                  List.of(
                      "tenantservice",
                      "agencyservice",
                      "consultingtypeservice",
                      "userservice",
                      "oriso-task-commands"))
              .claim(
                  "realm_access",
                  Map.of(
                      "roles",
                      List.of(
                          "config-wizard",
                          "invitation-reservations",
                          "consultant-import",
                          "smtp-sync")))
              .issueTime(Date.from(Instant.now().minusSeconds(1)))
              .expirationTime(Date.from(Instant.now().plusSeconds(300)))
              .build();
      var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      signed.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
      return broker.verifyConsultantImportTrigger(signed.serialize());
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "Synthetic configured import fixture is unavailable", invalid);
    }
  }
}
