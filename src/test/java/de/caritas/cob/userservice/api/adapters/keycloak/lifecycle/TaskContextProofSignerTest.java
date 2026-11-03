package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class TaskContextProofSignerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final UUID ATTEMPT = UUID.fromString("c17c20da-1f9e-46bf-9353-375d86eb8c75");
  private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

  @Test
  void createsBodyBoundProofForAccountCreation() throws Exception {
    String proof =
        signer()
            .sign(
                TaskPurpose.ACCOUNT_PROVISIONING,
                createBinding(),
                "account.create",
                "PUT",
                ATTEMPT,
                JSON.readTree("{\"z\":\"last\",\"a\":{\"y\":2,\"x\":1}}"));

    String[] parts = proof.split("\\.");
    assertThat(parts).hasSize(2);
    var claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));

    assertThat(claims.path("iss").asText()).isEqualTo("userservice");
    assertThat(claims.path("aud").asText()).isEqualTo("oriso-account-lifecycle");
    assertThat(claims.path("purpose").asText()).isEqualTo("ACCOUNT_PROVISIONING");
    assertThat(claims.path("actor").asText()).isEqualTo("ACCOUNT_CREATE");
    assertThat(claims.path("taskClient").asText()).isEqualTo("backend-account-create");
    assertThat(claims.path("taskSubject").asText())
        .isEqualTo("2ea5c184-bcc2-4d12-a242-b8f5ddf0f467");
    assertThat(claims.path("target").asText())
        .isEqualTo("realm/realm-uuid/account-attempt/" + ATTEMPT);
    assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
    assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plusSeconds(60).getEpochSecond());
    assertThat(claims.path("payloadDigest").asText())
        .isEqualTo(hmac("payload\n{\"a\":{\"x\":1,\"y\":2},\"z\":\"last\"}"));
    assertThat(Base64.getUrlDecoder().decode(parts[1]))
        .containsExactly(hmacBytes("proof\n" + parts[0]));
  }

  @Test
  void rejectsNonAccountPurpose() throws Exception {
    assertThatThrownBy(
            () ->
                signer()
                    .sign(
                        TaskPurpose.RUNTIME_POLICY,
                        new TaskActorCredentials(
                            TaskActor.RUNTIME,
                            "backend-runtime",
                            "runtime-secret",
                            "924d9185-b468-4f78-aa1b-6ef4e3fdd993"),
                        "account.create",
                        "PUT",
                        ATTEMPT,
                        JSON.readTree("{}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account task purpose");
  }

  @Test
  void requiresARealmIdAndContextKey() throws Exception {
    var environment = new MockEnvironment().withProperty("ORISO_TASK_CONTEXT_KEY", encodedKey());
    assertThatThrownBy(
            () ->
                new TaskContextProofSigner(
                        environment, keycloak(), Clock.fixed(NOW, ZoneOffset.UTC))
                    .sign(
                        TaskPurpose.ACCOUNT_PROVISIONING,
                        createBinding(),
                        "account.create",
                        "PUT",
                        ATTEMPT,
                        JSON.readTree("{}")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Missing lifecycle configuration: ORISO_ACCOUNT_LIFECYCLE_REALM_ID");
  }

  @Test
  void isConstructedBySpringUsingTheProductionConstructor() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getBeanFactory().registerSingleton("keycloakConfig", new KeycloakConfig());
      context.registerBean(TaskContextProofSigner.class);
      context.refresh();

      assertThat(context.getBean(TaskContextProofSigner.class)).isNotNull();
    }
  }

  private static TaskContextProofSigner signer() {
    var environment =
        new MockEnvironment()
            .withProperty("ORISO_TASK_CONTEXT_KEY", encodedKey())
            .withProperty("ORISO_ACCOUNT_LIFECYCLE_REALM_ID", "realm-uuid");
    return new TaskContextProofSigner(environment, keycloak(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static KeycloakConfig keycloak() {
    var keycloak = new KeycloakConfig();
    keycloak.setRealm("online-beratung");
    return keycloak;
  }

  private static TaskActorCredentials createBinding() {
    return new TaskActorCredentials(
        TaskActor.ACCOUNT_CREATE,
        "backend-account-create",
        "create-secret",
        "2ea5c184-bcc2-4d12-a242-b8f5ddf0f467");
  }

  private static String encodedKey() {
    return Base64.getEncoder().encodeToString(KEY);
  }

  private static String hmac(String value) {
    return HexFormat.of().formatHex(hmacBytes(value));
  }

  private static byte[] hmacBytes(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
