package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One shared key; distinct MAC domains for payload, proof and durable creation fingerprint. */
public final class AccountLifecycleCodec {
  private final byte[] key;
  private final Clock clock;
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);

  public AccountLifecycleCodec(byte[] key) {
    this(key, Clock.systemUTC());
  }

  AccountLifecycleCodec(byte[] key, Clock clock) {
    if (key == null || key.length < 32)
      throw new IllegalArgumentException("Task context key is too short");
    this.key = key.clone();
    this.clock = clock;
    var strict = new com.fasterxml.jackson.databind.module.SimpleModule();
    strict.addDeserializer(
        UUID.class,
        new com.fasterxml.jackson.databind.JsonDeserializer<UUID>() {
          @Override
          public UUID deserialize(
              com.fasterxml.jackson.core.JsonParser parser,
              com.fasterxml.jackson.databind.DeserializationContext context)
              throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING))
              throw com.fasterxml.jackson.databind.JsonMappingException.from(
                  parser, "Invalid canonical UUID");
            String text = parser.getText();
            try {
              UUID value = UUID.fromString(text);
              if (!value.toString().equals(text)) throw new IllegalArgumentException();
              return value;
            } catch (IllegalArgumentException failure) {
              throw com.fasterxml.jackson.databind.JsonMappingException.from(
                  parser, "Invalid canonical UUID");
            }
          }
        });
    json.registerModule(strict);
  }

  public CreationIdentity identity(CreationScope scope, AccountCreation account) {
    return new CreationIdentity(
        scope, digest("creation-payload", Map.of("scope", scope, "account", account)));
  }

  public String digest(String domain, Object value) {
    return HexFormat.of().formatHex(mac(domain + "\n" + canonical(value)));
  }

  public String proof(
      String operation,
      String method,
      String realmName,
      String realmId,
      UUID attemptId,
      TaskPurpose purpose,
      TaskActorCredentials actor,
      Object body) {
    if (actor.actor() != purpose.actor())
      throw new IllegalArgumentException("Wrong lifecycle actor");
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-account-lifecycle");
    claims.put("purpose", purpose.name());
    claims.put("operation", operation);
    claims.put("method", method);
    claims.put("actor", actor.actor().name());
    claims.put("realm", realmName);
    claims.put("target", "realm/" + realmId + "/account-attempt/" + attemptId);
    claims.put("taskClient", actor.clientId());
    claims.put("taskSubject", actor.serviceSubject());
    claims.put("payloadDigest", digest("payload", body));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    claims.put("iat", now);
    claims.put("exp", now + 60);
    String encoded = encode(canonical(claims).getBytes(StandardCharsets.UTF_8));
    return encoded + "." + encode(mac("proof\n" + encoded));
  }

  public record CreationProofPayload(
      int v,
      String realmName,
      String realmId,
      java.util.UUID attemptId,
      String accountId,
      String ownerClient,
      String ownerSubject,
      CreationScope scope,
      String initialGroupId,
      String initialRoleFingerprint,
      String creationFingerprint) {
    @Override
    public String toString() {
      return "CreationProofPayload[ownership=redacted]";
    }
  }

  /** Finite maintenance proof encoding; only the checked private domain writer issues a scope. */
  public String maintenanceProof(
      String command,
      String realmName,
      String realmId,
      String accountId,
      TaskActorCredentials actor,
      Object body) {
    if (!java.util.Set.of("read", "delete", "locale").contains(command)
        || actor.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN)
      throw new IllegalArgumentException("Invalid maintenance proof binding");
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-account-maintenance");
    claims.put("purpose", TaskPurpose.ACCOUNT_MAINTENANCE.name());
    claims.put("operation", "account." + command);
    claims.put("method", "POST");
    claims.put("actor", actor.actor().name());
    claims.put("realm", realmName);
    claims.put("target", "realm/" + realmId + "/account/" + accountId);
    claims.put("taskClient", actor.clientId());
    claims.put("taskSubject", actor.serviceSubject());
    claims.put("payloadDigest", digest("payload", body));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    claims.put("iat", now);
    claims.put("exp", now + 60);
    String encoded = encode(canonical(claims).getBytes(StandardCharsets.UTF_8));
    return encoded + "." + encode(mac("proof\n" + encoded));
  }

  private record MaintenanceReply(
      UUID operationId, String accountId, String result, String receipt) {}

  private record MaintenanceOutcome(
      String realmId,
      String accountId,
      UUID operationId,
      String command,
      String ownerClient,
      String ownerSubject,
      String intentDigest,
      String previousState,
      String tenantId,
      String originDigest,
      String result) {}

  /**
   * A transport success is not acknowledgement until every immutable receiver binding is checked.
   */
  public String verifyMaintenanceDeleteReceipt(
      String reply, String realmId, TaskActorCredentials actor, JsonNode frozen) {
    return verifyMaintenanceReceipt(reply, realmId, actor, frozen, "delete", "DELETED");
  }

  public String verifyMaintenanceLocaleReceipt(
      String reply, String realmId, TaskActorCredentials actor, JsonNode frozen) {
    return verifyMaintenanceReceipt(reply, realmId, actor, frozen, "locale", "UPDATED");
  }

  private String verifyMaintenanceReceipt(
      String reply,
      String realmId,
      TaskActorCredentials actor,
      JsonNode frozen,
      String command,
      String expectedResult) {
    try {
      var result = read(reply, MaintenanceReply.class);
      UUID operation = UUID.fromString(frozen.path("operationId").asText());
      String account = frozen.path("target").path("accountId").asText();
      if (actor.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN
          || !operation.equals(result.operationId())
          || !account.equals(result.accountId())
          || !expectedResult.equals(result.result())
          || result.receipt() == null
          || result.receipt().length() > 16384) throw new IllegalArgumentException();
      String[] parts = result.receipt().split("\\.", -1);
      if (parts.length != 2
          || !parts[0].matches("[A-Za-z0-9_-]+")
          || !parts[1].matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
      if (!encode(decoded).equals(parts[0])
          || !java.security.MessageDigest.isEqual(
              HexFormat.of().parseHex(parts[1]), mac("maintenance-operation-receipt\n" + parts[0])))
        throw new IllegalArgumentException();
      String raw = utf8(decoded);
      var outcome = read(raw, MaintenanceOutcome.class);
      JsonNode tenant = frozen.path("target").path("tenantId");
      String expectedTenant = tenant.isNull() ? null : tenant.textValue();
      if (!raw.equals(canonical(outcome))
          || !realmId.equals(outcome.realmId())
          || !account.equals(outcome.accountId())
          || !operation.equals(outcome.operationId())
          || !command.equals(outcome.command())
          || !actor.clientId().equals(outcome.ownerClient())
          || !actor.serviceSubject().equals(outcome.ownerSubject())
          || !digest("maintenance-intent", frozen).equals(outcome.intentDigest())
          || !frozen.path("expectedState").asText().equals(outcome.previousState())
          || !java.util.Objects.equals(expectedTenant, outcome.tenantId())
          || !digest("maintenance-origin", frozen.path("target").path("origin"))
              .equals(outcome.originDigest())
          || !expectedResult.equals(outcome.result())) throw new IllegalArgumentException();
      return canonical(result);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Invalid native maintenance receipt");
    }
  }

  /** Verify receiver-owned provenance before trusting an account id returned over HTTP. */
  public CreationProofPayload verifyCreationProof(
      String proof,
      String realmName,
      String realmId,
      String ownerClient,
      String ownerSubject,
      java.util.UUID attemptId,
      String accountId,
      CreationIdentity identity) {
    if (proof == null || proof.length() > 16384) throw invalidProof();
    String[] parts = proof.split("\\.", -1);
    if (parts.length != 2
        || !parts[0].matches("[A-Za-z0-9_-]+")
        || !parts[1].matches("[A-Za-z0-9_-]+")) throw invalidProof();
    try {
      byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
      byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
      if (!encode(payloadBytes).equals(parts[0])
          || !encode(signature).equals(parts[1])
          || !java.security.MessageDigest.isEqual(signature, mac("creation-proof\n" + parts[0])))
        throw invalidProof();
      String payloadJson = utf8(payloadBytes);
      var payload = read(payloadJson, CreationProofPayload.class);
      if (!payloadJson.equals(canonical(payload))
          || payload.v() != 1
          || !java.util.Objects.equals(realmName, payload.realmName())
          || !java.util.Objects.equals(realmId, payload.realmId())
          || !java.util.Objects.equals(ownerClient, payload.ownerClient())
          || !java.util.Objects.equals(ownerSubject, payload.ownerSubject())
          || !java.util.Objects.equals(attemptId, payload.attemptId())
          || !java.util.Objects.equals(accountId, payload.accountId())
          || !java.util.Objects.equals(identity.scope(), payload.scope())
          || !java.util.Objects.equals(
              identity.creationFingerprint(), payload.creationFingerprint())
          || payload.initialGroupId() == null
          || payload.initialGroupId().isBlank()
          || payload.initialGroupId().length() > 512
          || payload.initialRoleFingerprint() == null
          || !payload.initialRoleFingerprint().matches("[0-9a-f]{64}")) throw invalidProof();
      return payload;
    } catch (IllegalArgumentException failure) {
      throw invalidProof();
    }
  }

  public String utf8(byte[] value) {
    if (value == null || value.length > 65536)
      throw new IllegalArgumentException("Invalid lifecycle UTF8 body");
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(value))
          .toString();
    } catch (java.nio.charset.CharacterCodingException failure) {
      throw new IllegalArgumentException("Invalid lifecycle UTF8 body");
    }
  }

  private static IllegalArgumentException invalidProof() {
    return new IllegalArgumentException("Invalid receiver creation proof");
  }

  public String canonical(Object value) {
    try {
      return json.writeValueAsString(sorted(json.valueToTree(value)));
    } catch (RuntimeException | java.io.IOException failure) {
      throw new IllegalArgumentException("Invalid account lifecycle JSON");
    }
  }

  public <T> T read(String value, Class<T> type) {
    if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new IllegalArgumentException("Account lifecycle response exceeds limit");
    try {
      return json.readValue(value, type);
    } catch (java.io.IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Invalid account lifecycle response");
    }
  }

  private JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode out = json.createObjectNode();
      var keys = new java.util.TreeSet<String>();
      value.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> out.set(k, sorted(value.get(k))));
      return out;
    }
    if (value.isArray()) {
      var out = json.createArrayNode();
      value.forEach(v -> out.add(sorted(v)));
      return out;
    }
    if (value.isFloatingPointNumber())
      throw new IllegalArgumentException("Floating lifecycle value");
    return value;
  }

  private byte[] mac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Task proof unavailable");
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
