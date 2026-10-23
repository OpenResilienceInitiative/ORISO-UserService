package de.caritas.cob.userservice.api.config.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Real signed incoming tokens only. These do not authorize any target or import record. */
class ConsultantImportTriggerBoundaryTest {
  private static final String SUBJECT = "84000000-0000-4000-8000-000000000001";
  private static final String ISSUER = "https://public-auth.example.test/realms/synthetic";
  private static final String CLIENT = "backend-setup";
  private KeyPair keys;
  private TaskActorConfiguration actors;
  private KeycloakAuthClient authentication;
  private TaskActorTokenBroker broker;

  @BeforeEach
  void configuredSignatureAndNativeBinding() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    actors = mock(TaskActorConfiguration.class);
    authentication = mock(KeycloakAuthClient.class);
    when(actors.require(TaskPurpose.CONSULTANT_IMPORT))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.SETUP, CLIENT, "synthetic-only-setup-secret", SUBJECT));
    var nativeConfig = new KeycloakConfig();
    nativeConfig.setAuthServerUrl("https://public-auth.example.test");
    nativeConfig.setRealm("synthetic");
    broker =
        new TaskActorTokenBroker(
            actors,
            authentication,
            NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build(),
            nativeConfig);
  }

  @Test
  void validIncomingSetupTriggerIsPrivateLiveAndNeverIssuesAnotherToken() throws Exception {
    String raw = token(Map.of(), keys);
    var trigger = assertDoesNotThrow(() -> broker.verifyConsultantImportTrigger(raw));
    assertDoesNotThrow(trigger::requireLive);
    assertEquals(0, trigger.getClass().getConstructors().length);
    assertFalse(trigger.toString().contains(raw));
    for (var method : trigger.getClass().getDeclaredMethods())
      assertFalse(
          Set.of("accessToken", "bearer", "token", "clientSecret").contains(method.getName()));
    var foreign = new AtomicReference<Throwable>();
    var thread =
        new Thread(
            () -> {
              try {
                trigger.requireLive();
              } catch (Throwable failure) {
                foreign.set(failure);
              }
            });
    thread.start();
    thread.join();
    assertInstanceOf(IllegalStateException.class, foreign.get());
    assertDoesNotThrow(trigger::requireLive);
    trigger.close();
    assertThrows(IllegalStateException.class, trigger::requireLive);
    verify(actors, atLeastOnce()).require(TaskPurpose.CONSULTANT_IMPORT);
    verifyNoInteractions(authentication);
  }

  @Test
  void signedWrongActorsScopesOrTimesNeverBecomeConfiguredImportAuthority() throws Exception {
    try (var valid =
        assertDoesNotThrow(() -> broker.verifyConsultantImportTrigger(token(Map.of(), keys)))) {
      assertDoesNotThrow(valid::requireLive);
    }
    var variants = new ArrayList<Map<String, Object>>();
    variants.add(Map.of("sub", "human-admin"));
    variants.add(Map.of("azp", "backend-account-admin"));
    variants.add(Map.of("iss", "https://internal-auth.example.test/realms/synthetic"));
    variants.add(Map.of("realm_access", Map.of("roles", List.of("technical"))));
    variants.add(Map.of("realm_access", Map.of("roles", List.of("user-admin"))));
    variants.add(Map.of("realm_access", Map.of("roles", List.of("consultant-import"))));
    variants.add(
        Map.of(
            "realm_access",
            Map.of(
                "roles",
                List.of(
                    "config-wizard",
                    "invitation-reservations",
                    "consultant-import",
                    "account-maintenance"))));
    variants.add(
        Map.of(
            "realm_access",
            Map.of(
                "roles",
                List.of(
                    "config-wizard",
                    "invitation-reservations",
                    "consultant-import",
                    "consultant-import"))));
    variants.add(Map.of("aud", List.of("userservice")));
    variants.add(
        Map.of(
            "aud",
            List.of(
                "tenantservice",
                "agencyservice",
                "consultingtypeservice",
                "userservice",
                "oriso-account-lifecycle")));
    variants.add(
        Map.of(
            "aud",
            List.of(
                "tenantservice",
                "agencyservice",
                "consultingtypeservice",
                "userservice",
                "userservice")));
    variants.add(
        Map.of(
            "resource_access",
            Map.of("realm-management", Map.of("roles", List.of("manage-users")))));
    variants.add(Map.of("iat", Date.from(Instant.now().plusSeconds(30))));
    variants.add(Map.of("iat", Date.from(Instant.EPOCH)));
    variants.add(Map.of("exp", Date.from(Instant.now().minusSeconds(1))));
    for (var changed : variants) {
      String raw = token(changed, keys);
      var denied =
          assertThrows(
              IllegalStateException.class,
              () -> broker.verifyConsultantImportTrigger(raw),
              changed.keySet().toString());
      assertEquals("Consultant import trigger unavailable", denied.getMessage());
      assertNull(denied.getCause());
      assertFalse(denied.toString().contains(raw));
    }
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String wrongSignature = token(Map.of(), generator.generateKeyPair());
    assertThrows(
        IllegalStateException.class, () -> broker.verifyConsultantImportTrigger(wrongSignature));
    assertThrows(IllegalStateException.class, () -> broker.verifyConsultantImportTrigger(null));
    assertThrows(
        IllegalStateException.class,
        () -> broker.verifyConsultantImportTrigger("Bearer synthetic"));
    assertThrows(IllegalStateException.class, () -> broker.verifyConsultantImportTrigger(""));
    verifyNoInteractions(authentication);
  }

  private String token(Map<String, Object> changes, KeyPair signing) throws Exception {
    var values = new HashMap<String, Object>();
    values.put("iss", ISSUER);
    values.put("sub", SUBJECT);
    values.put("azp", CLIENT);
    values.put(
        "aud", List.of("tenantservice", "agencyservice", "consultingtypeservice", "userservice"));
    values.put(
        "realm_access",
        Map.of("roles", List.of("config-wizard", "invitation-reservations", "consultant-import")));
    values.put("iat", Date.from(Instant.now().minusSeconds(1)));
    values.put("exp", Date.from(Instant.now().plusSeconds(300)));
    values.putAll(changes);
    var builder = new JWTClaimsSet.Builder();
    values.forEach(builder::claim);
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
    signed.sign(new RSASSASigner((RSAPrivateKey) signing.getPrivate()));
    return signed.serialize();
  }
}
