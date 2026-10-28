package de.caritas.cob.userservice.api.config.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Explicit native gate; no conditional skip or synthetic replacement for an absent fixture. */
class RealMailActorNativeGate {
  private TaskActorTokenBroker broker;

  @BeforeEach
  void nativeFixtureRequired() throws Exception {
    String configured = System.getenv("ORISO_TASK_TOKEN_FIXTURE");
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("Native five-account fixture is required");
    }
    Path path = Path.of(configured);
    JsonNode fixture = new ObjectMapper().readTree(Files.readString(path));
    assertThat(fixture.path("identityModel").asText()).isEqualTo("FIVE_ACTORS");
    assertThat(fixture.path("tasks").size()).isEqualTo(5);
    var environment = new MockEnvironment().withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS");
    var actors = new java.util.HashSet<String>();
    for (JsonNode actor : fixture.path("tasks")) {
      String key = actor.path("key").asText();
      assertThat(actors.add(key)).isTrue();
      environment.setProperty("IDENTITY_" + key + "_CLIENT_ID", actor.path("clientId").asText());
      environment.setProperty("IDENTITY_" + key + "_CLIENT_SECRET", actor.path("secret").asText());
      environment.setProperty(
          "IDENTITY_" + key + "_SERVICE_SUBJECT", actor.path("subject").asText());
    }
    assertThat(actors)
        .isEqualTo(Set.of("SETUP", "ACCOUNT_CREATE", "ACCOUNT_ADMIN", "MAIL", "RUNTIME"));
    String issuer = fixture.path("issuer").asText();
    int realmStart = issuer.lastIndexOf("/realms/");
    assertThat(realmStart).isPositive();
    var identity = new IdentityConfig();
    identity.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
    var authentication =
        new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), identity);
    ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
    var keycloak = new KeycloakConfig();
    keycloak.setAuthServerUrl(issuer.substring(0, realmStart));
    keycloak.setRealm(issuer.substring(realmStart + "/realms/".length()));
    broker =
        new TaskActorTokenBroker(
            FiveActorConfigurationTest.configured(environment), authentication,
            NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build(),
                keycloak);
  }

  @ParameterizedTest
  @EnumSource(
      value = TaskPurpose.class,
      names = {"SMTP_SYNC", "SYSTEM_EMAIL_DELIVERY", "NOTIFICATION_DISPATCH"})
  void realNativeCanonicalPurposeIsAcceptedByTheActualBroker(TaskPurpose purpose) {
    var result = broker.issue(purpose);
    assertThat(result.actor()).isEqualTo(purpose.actor());
    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.toString()).doesNotContain(result.accessToken());
  }

  @Test
  void realDeliveryBearerCannotApplySmtpEvenWithValidSharedKeyProof() throws Exception {
    String driver = System.getenv("ORISO_MAIL_TRANSPORT_DRIVER");
    if (driver == null || !Files.isRegularFile(Path.of(driver))) {
      throw new IllegalStateException("Real joined MAIL transport driver is required");
    }
    var token = broker.issue(TaskPurpose.SYSTEM_EMAIL_DELIVERY);
    assertThat(token.actor()).isEqualTo(TaskActor.MAIL);
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("ORISO_NATIVE_PYTHON", "python3"),
                driver,
                System.getenv("ORISO_TASK_TOKEN_FIXTURE"),
                "mail-refused")
            .redirectErrorStream(true)
            .start();
    try {
      try (var input = process.getOutputStream()) {
        input.write(token.accessToken().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(
              new String(
                      process.getInputStream().readNBytes(1024),
                      java.nio.charset.StandardCharsets.UTF_8)
                  .trim())
          .isEqualTo("NATIVE_MAIL_SMTP_REFUSED_PASSED");
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }

  @Test
  void sameNativeBrokerBearerReadsRealSavedCtsAppliesSmtpAndAcknowledgesExactRevision()
      throws Exception {
    String driver = System.getenv("ORISO_MAIL_TRANSPORT_DRIVER");
    if (driver == null || !Files.isRegularFile(Path.of(driver))) {
      throw new IllegalStateException("Real joined MAIL transport driver is required");
    }
    var token = broker.issue(TaskPurpose.SMTP_SYNC);
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("ORISO_NATIVE_PYTHON", "python3"),
                driver,
                System.getenv("ORISO_TASK_TOKEN_FIXTURE"))
            .redirectErrorStream(true)
            .start();
    try {
      try (var input = process.getOutputStream()) {
        input.write(token.accessToken().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(
              new String(
                      process.getInputStream().readNBytes(1024),
                      java.nio.charset.StandardCharsets.UTF_8)
                  .trim())
          .isEqualTo("NATIVE_MAIL_BROKER_CTS_SMTP_ACK_PASSED");
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }
}
