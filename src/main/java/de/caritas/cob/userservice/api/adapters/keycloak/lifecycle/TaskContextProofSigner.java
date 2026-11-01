package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Creates the body-bound proof accepted by Keycloak's bounded lifecycle receiver. */
@Component
public class TaskContextProofSigner {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Environment environment;
  private final KeycloakConfig keycloak;
  private final Clock clock;

  @Autowired
  public TaskContextProofSigner(Environment environment, KeycloakConfig keycloak) {
    this(environment, keycloak, Clock.systemUTC());
  }

  TaskContextProofSigner(Environment environment, KeycloakConfig keycloak, Clock clock) {
    this.environment = environment;
    this.keycloak = keycloak;
    this.clock = clock;
  }

  public String sign(
      TaskPurpose purpose,
      TaskActorCredentials binding,
      String operation,
      String method,
      UUID attemptId,
      JsonNode body) {
    if (purpose != TaskPurpose.ACCOUNT_PROVISIONING && purpose != TaskPurpose.ACCOUNT_MAINTENANCE) {
      throw new IllegalArgumentException("Lifecycle proofs require an account task purpose");
    }
    if (binding == null || binding.actor() != purpose.actor()) {
      throw new IllegalArgumentException("Lifecycle proof actor does not match its purpose");
    }
    if (attemptId == null || blank(operation) || blank(method)) {
      throw new IllegalArgumentException("Lifecycle proof fields are required");
    }

    byte[] key = contextKey();
    String realm = required("ORISO_ACCOUNT_LIFECYCLE_REALM", keycloak.getRealm());
    String realmId = required("ORISO_ACCOUNT_LIFECYCLE_REALM_ID", null);
    String canonicalBody = canonical(body);
    Instant now = clock.instant();
    ObjectNode claims = JSON.createObjectNode();
    claims.put("actor", binding.actor().name());
    claims.put("aud", "oriso-account-lifecycle");
    claims.put("exp", now.plusSeconds(60).getEpochSecond());
    claims.put("iat", now.getEpochSecond());
    claims.put("iss", "userservice");
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("method", method);
    claims.put("nonce", nonce());
    claims.put("operation", operation);
    claims.put("payloadDigest", hmac(key, "payload\n" + canonicalBody));
    claims.put("purpose", purpose.name());
    claims.put("realm", realm);
    claims.put("target", "realm/" + realmId + "/account-attempt/" + attemptId);
    claims.put("taskClient", binding.clientId());
    claims.put("taskSubject", binding.serviceSubject());
    String encodedClaims = base64Url(canonical(claims).getBytes(StandardCharsets.UTF_8));
    return encodedClaims + "." + base64Url(hmacBytes(key, "proof\n" + encodedClaims));
  }

  private byte[] contextKey() {
    String encoded = required("ORISO_TASK_CONTEXT_KEY", null);
    try {
      byte[] key = Base64.getDecoder().decode(encoded);
      if (key.length < 32)
        throw new IllegalStateException("ORISO_TASK_CONTEXT_KEY must contain 32 bytes");
      return key;
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("ORISO_TASK_CONTEXT_KEY must be base64", failure);
    }
  }

  private String required(String name, String fallback) {
    String value = environment.getProperty(name);
    if (blank(value)) value = fallback;
    if (blank(value) || value.contains("${")) {
      throw new IllegalStateException("Missing lifecycle configuration: " + name);
    }
    return value;
  }

  private static String canonical(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Lifecycle body must be a JSON object");
    }
    try {
      return JSON.writeValueAsString(sorted(value));
    } catch (Exception failure) {
      throw new IllegalArgumentException("Lifecycle body is not canonical JSON", failure);
    }
  }

  private static JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      Map<String, JsonNode> fields = new TreeMap<>();
      value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
      fields.forEach((name, child) -> result.set(name, sorted(child)));
      return result;
    }
    if (value.isArray()) {
      ArrayNode result = JSON.createArrayNode();
      value.forEach(child -> result.add(sorted(child)));
      return result;
    }
    if (!value.isNull() && !value.isBoolean() && !value.isTextual() && !value.isIntegralNumber()) {
      throw new IllegalArgumentException("Lifecycle body contains an unsupported JSON value");
    }
    return value;
  }

  private static String hmac(byte[] key, String value) {
    return HexFormat.of().formatHex(hmacBytes(key, value));
  }

  private static byte[] hmacBytes(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new IllegalStateException("Lifecycle context signing is unavailable", failure);
    }
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
