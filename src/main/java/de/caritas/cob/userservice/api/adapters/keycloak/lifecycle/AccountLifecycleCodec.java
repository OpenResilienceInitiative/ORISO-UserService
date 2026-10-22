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
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One shared key; distinct MAC domains for payload, proof and durable creation fingerprint. */
public final class AccountLifecycleCodec {
  private final byte[] key;
  private final Clock clock;
  private final java.security.SecureRandom intentNonce;
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
    this(key, clock, new java.security.SecureRandom());
  }

  AccountLifecycleCodec(byte[] key, Clock clock, java.security.SecureRandom intentNonce) {
    if (key == null || key.length < 32)
      throw new IllegalArgumentException("Task context key is too short");
    this.key = key.clone();
    this.clock = clock;
    this.intentNonce = java.util.Objects.requireNonNull(intentNonce);
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

  /** Data protection only; the package-owned store must independently check its locked metadata. */
  String sealSelfAppIntent(JsonNode independentlyCheckedOwnedMetadata, byte[] frozenCommand) {
    try {
      var metadata = selfAppMetadata(independentlyCheckedOwnedMetadata);
      byte[] plaintext = selfAppCommand(metadata, frozenCommand);
      byte[] nonce = new byte[12];
      intentNonce.nextBytes(nonce);
      byte[] ciphertext = selfAppCipher(Cipher.ENCRYPT_MODE, metadata, nonce, plaintext);
      return canonical(Map.of("v", 1, "nonce", encode(nonce), "ciphertext", encode(ciphertext)));
    } catch (RuntimeException failure) {
      throw invalidSecretIntent();
    }
  }

  /** A decrypted body never supplies ownership, current authority or dispatch permission. */
  byte[] openSelfAppIntent(JsonNode independentlyCheckedOwnedMetadata, String envelope) {
    try {
      var metadata = selfAppMetadata(independentlyCheckedOwnedMetadata);
      if (envelope == null || envelope.length() > 87500) throw invalidSecretIntent();
      JsonNode value = json.readTree(envelope);
      intentFields(value, java.util.Set.of("v", "nonce", "ciphertext"));
      if (!value.path("v").isIntegralNumber()
          || !value.path("v").canConvertToInt()
          || value.path("v").intValue() != 1
          || !envelope.equals(canonical(value))) throw invalidSecretIntent();
      byte[] nonce = intentBase64(value.path("nonce"), 12, 12);
      byte[] ciphertext = intentBase64(value.path("ciphertext"), 16, 65552);
      return selfAppCommand(
          metadata, selfAppCipher(Cipher.DECRYPT_MODE, metadata, nonce, ciphertext));
    } catch (RuntimeException | java.io.IOException failure) {
      throw invalidSecretIntent();
    }
  }

  /** Immutable data only; construction is deliberately private and grants no workflow authority. */
  private record SelfAppMetadata(
      int v,
      String realmId,
      String operationId,
      String command,
      String method,
      String route,
      String accountId,
      Long tenantId,
      String ownerClient,
      String ownerSubject,
      String originDigest,
      String workflowId,
      String requestDigest,
      String expectedState,
      long authorityValidUntil,
      String representation) {
    @Override
    public String toString() {
      return "SelfAppMetadata[redacted]";
    }
  }

  private SelfAppMetadata selfAppMetadata(JsonNode value) {
    intentFields(
        value,
        java.util.Set.of(
            "v",
            "realmId",
            "operationId",
            "command",
            "method",
            "route",
            "accountId",
            "tenantId",
            "ownerClient",
            "ownerSubject",
            "originDigest",
            "workflowId",
            "requestDigest",
            "expectedState",
            "authorityValidUntil",
            "representation"));
    if (!value.path("v").isIntegralNumber()
        || !value.path("v").canConvertToInt()
        || value.path("v").intValue() != 1) throw invalidSecretIntent();
    String operationId = intentUuid(value, "operationId");
    String workflowId = intentUuid(value, "workflowId");
    String operation = intentText(value, "command", 64);
    boolean deletion = "otp.delete".equals(operation);
    boolean emailStart = "otp.email-start".equals(operation);
    boolean emailFinish = "otp.email-finish".equals(operation);
    String route =
        deletion
            ? "otp-config/delete-otp"
            : emailStart
                ? "otp-config/send-verification-mail"
                : emailFinish ? "otp-config/setup-otp-mail" : "otp-config/setup-otp";
    if (!operationId.equals(workflowId)
        || !(deletion || emailStart || emailFinish || "otp.app-setup".equals(operation))
        || !(deletion ? "DELETE" : emailFinish ? "POST" : "PUT")
            .equals(intentText(value, "method", 16))
        || !route.equals(intentText(value, "route", 128))
        || !"FROZEN_HTTP_V1".equals(intentText(value, "representation", 64)))
      throw invalidSecretIntent();
    JsonNode tenant = value.get("tenantId");
    Long tenantId = null;
    if (!tenant.isNull()) {
      if (!tenant.isIntegralNumber() || !tenant.canConvertToLong() || tenant.longValue() < 0)
        throw invalidSecretIntent();
      tenantId = tenant.longValue();
    }
    JsonNode expiry = value.get("authorityValidUntil");
    if (!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue() <= 0)
      throw invalidSecretIntent();
    return new SelfAppMetadata(
        1,
        intentText(value, "realmId", 512),
        operationId,
        operation,
        deletion ? "DELETE" : emailFinish ? "POST" : "PUT",
        route,
        intentUuid(value, "accountId"),
        tenantId,
        intentText(value, "ownerClient", 512),
        intentUuid(value, "ownerSubject"),
        intentDigest(value, "originDigest"),
        workflowId,
        intentDigest(value, "requestDigest"),
        intentDigest(value, "expectedState"),
        expiry.longValue(),
        "FROZEN_HTTP_V1");
  }

  private byte[] selfAppCommand(SelfAppMetadata metadata, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 65536) throw invalidSecretIntent();
    byte[] snapshot = bytes.clone();
    String raw = utf8(snapshot);
    JsonNode value = read(raw, JsonNode.class);
    intentFields(
        value,
        value.has("localState")
            ? java.util.Set.of("username", "body", "localState")
            : java.util.Set.of("username", "body"));
    if (value.has("localState")) selfOtpLocalState(value.path("localState"));
    intentText(value, "username", 512);
    JsonNode body = value.path("body");
    boolean emailStart = "otp.email-start".equals(metadata.command());
    boolean emailFinish = "otp.email-finish".equals(metadata.command());
    intentFields(
        body,
        emailStart
            ? java.util.Set.of(
                "target", "operationId", "expectedState", "otp", "authorityValidUntil")
            : emailFinish
                ? java.util.Set.of(
                    "target", "operationId", "expectedState", "otp", "emailStartOperationId")
                : java.util.Set.of("target", "operationId", "expectedState", "otp"));
    if (emailFinish) intentUuid(body, "emailStartOperationId");
    if (emailStart
        && (!body.path("authorityValidUntil").isIntegralNumber()
            || !body.path("authorityValidUntil").canConvertToLong()
            || body.path("authorityValidUntil").longValue() != metadata.authorityValidUntil()))
      throw invalidSecretIntent();
    if (!metadata.operationId().equals(intentUuid(body, "operationId"))
        || !metadata.expectedState().equals(intentDigest(body, "expectedState")))
      throw invalidSecretIntent();
    JsonNode target = body.path("target");
    intentFields(target, java.util.Set.of("accountId", "tenantId", "origin"));
    JsonNode tenant = target.get("tenantId");
    if (!metadata.accountId().equals(intentUuid(target, "accountId"))
        || (metadata.tenantId() == null
            ? !tenant.isNull()
            : !tenant.isTextual() || !metadata.tenantId().toString().equals(tenant.textValue())))
      throw invalidSecretIntent();
    JsonNode origin = target.path("origin");
    intentFields(
        origin,
        java.util.Set.of(
            "kind", "humanAuthority", "humanClient", "humanIssuer", "humanSubject", "workflowId"));
    if (!"SELF".equals(intentText(origin, "kind", 16))
        || !origin.get("humanAuthority").isNull()
        || !metadata.accountId().equals(intentUuid(origin, "humanSubject"))
        || !metadata.workflowId().equals(intentUuid(origin, "workflowId"))
        || !metadata.originDigest().equals(digest("otp-origin", origin)))
      throw invalidSecretIntent();
    intentText(origin, "humanClient", 512);
    intentText(origin, "humanIssuer", 2048);
    JsonNode otp = body.path("otp");
    if ("otp.delete".equals(metadata.command())) {
      if (!otp.isNull()) throw invalidSecretIntent();
    } else {
      intentFields(otp, java.util.Set.of("initialCode", "secret", "email"));
      if (emailStart) {
        if (!otp.get("initialCode").isNull() || !otp.get("secret").isNull())
          throw invalidSecretIntent();
        intentText(otp, "email", 320);
      } else if (emailFinish) {
        if (!intentText(otp, "initialCode", 8).matches("[0-9]{6,8}")
            || !otp.get("secret").isNull()
            || !otp.get("email").isNull()) throw invalidSecretIntent();
      } else {
        intentText(otp, "initialCode", 128);
        intentText(otp, "secret", 4096);
        if (!otp.get("email").isNull()) throw invalidSecretIntent();
      }
    }
    if (!raw.equals(canonical(value))) throw invalidSecretIntent();
    return snapshot;
  }

  private void selfOtpLocalState(JsonNode value) {
    intentFields(
        value,
        java.util.Set.of("v", "enrollmentOperationId", "pendingEmailStartOperationId", "invites"));
    if (!value.path("v").isIntegralNumber()
        || !value.path("v").canConvertToInt()
        || value.path("v").intValue() != 1) throw invalidSecretIntent();
    for (String field : java.util.List.of("enrollmentOperationId", "pendingEmailStartOperationId"))
      if (!value.get(field).isNull()) intentUuid(value, field);
    var invites = value.path("invites");
    if (!invites.isArray() || invites.size() > 1024) throw invalidSecretIntent();
    long previous = 0;
    for (var invite : invites) {
      intentFields(invite, java.util.Set.of("id", "status"));
      String id = intentText(invite, "id", 19);
      if (!id.matches("[1-9][0-9]{0,18}")) throw invalidSecretIntent();
      long current = Long.parseLong(id);
      if (current <= previous
          || !java.util.Set.of(
                  "NOT_REQUIRED", "PENDING_SETUP", "ACTIVE", "WAIVED", "DISABLED_BY_POLICY")
              .contains(intentText(invite, "status", 32))) throw invalidSecretIntent();
      previous = current;
    }
  }

  private byte[] selfAppCipher(int mode, SelfAppMetadata metadata, byte[] nonce, byte[] input) {
    String metadataDigest = digest("oriso/user-maintenance-intent/metadata/v1", metadata);
    byte[] encryptionKey = mac("oriso/user-maintenance-intent/encryption/v1\n" + metadataDigest);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(canonical(metadata).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (java.security.GeneralSecurityException failure) {
      throw invalidSecretIntent();
    } finally {
      java.util.Arrays.fill(encryptionKey, (byte) 0);
    }
  }

  private static void intentFields(JsonNode value, java.util.Set<String> expected) {
    if (value == null || !value.isObject() || value.size() != expected.size())
      throw invalidSecretIntent();
    var actual = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalidSecretIntent();
  }

  private static String intentText(JsonNode value, String field, int limit) {
    JsonNode node = value.get(field);
    if (node == null || !node.isTextual()) throw invalidSecretIntent();
    String text = node.textValue();
    if (text.isBlank() || text.length() > limit || text.chars().anyMatch(c -> c < 32))
      throw invalidSecretIntent();
    return text;
  }

  private static String intentUuid(JsonNode value, String field) {
    String text = intentText(value, field, 36);
    if (!UUID.fromString(text).toString().equals(text)) throw invalidSecretIntent();
    return text;
  }

  private static String intentDigest(JsonNode value, String field) {
    String text = intentText(value, field, 64);
    if (!text.matches("[0-9a-f]{64}")) throw invalidSecretIntent();
    return text;
  }

  private static byte[] intentBase64(JsonNode value, int minimum, int maximum) {
    if (!value.isTextual() || value.textValue().length() > 87403) throw invalidSecretIntent();
    String encoded = value.textValue();
    if (!encoded.matches("[A-Za-z0-9_-]+")) throw invalidSecretIntent();
    byte[] bytes = Base64.getUrlDecoder().decode(encoded);
    if (bytes.length < minimum || bytes.length > maximum || !encode(bytes).equals(encoded))
      throw invalidSecretIntent();
    return bytes;
  }

  private static IllegalArgumentException invalidSecretIntent() {
    return new IllegalArgumentException("Invalid owned secret intent");
  }

  /** Fixed app command encoding; only the independently checked owned workflow calls this. */
  String selfAppOtpProof(
      String realmName,
      String realmId,
      String accountId,
      TaskActorCredentials actor,
      JsonNode materialized,
      long authorityValidUntil) {
    return selfOtpProof(
        realmName,
        realmId,
        accountId,
        actor,
        materialized,
        authorityValidUntil,
        "otp.app-setup",
        "PUT");
  }

  String selfDeleteOtpProof(
      String realmName,
      String realmId,
      String accountId,
      TaskActorCredentials actor,
      JsonNode materialized,
      long authorityValidUntil) {
    return selfOtpProof(
        realmName,
        realmId,
        accountId,
        actor,
        materialized,
        authorityValidUntil,
        "otp.delete",
        "DELETE");
  }

  String selfEmailStartOtpProof(
      String realmName,
      String realmId,
      String accountId,
      TaskActorCredentials actor,
      JsonNode materialized,
      long authorityValidUntil) {
    return selfOtpProof(
        realmName,
        realmId,
        accountId,
        actor,
        materialized,
        authorityValidUntil,
        "otp.email-start",
        "PUT");
  }

  String selfLinkedEmailFinishOtpProof(
      String realmName,
      String realmId,
      String accountId,
      TaskActorCredentials actor,
      JsonNode materialized,
      long authorityValidUntil) {
    return selfOtpProof(
        realmName,
        realmId,
        accountId,
        actor,
        materialized,
        authorityValidUntil,
        "otp.email-finish",
        "POST");
  }

  private String selfOtpProof(
      String realmName,
      String realmId,
      String accountId,
      TaskActorCredentials actor,
      JsonNode materialized,
      long authorityValidUntil,
      String operation,
      String method) {
    long now = clock.instant().getEpochSecond();
    long expiry = Math.min(now + 60, authorityValidUntil);
    if (actor.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN
        || expiry <= now) throw invalidSecretIntent();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-otp");
    claims.put("purpose", TaskPurpose.OTP.name());
    claims.put("operation", operation);
    claims.put("method", method);
    claims.put("actor", actor.actor().name());
    claims.put("realm", realmName);
    claims.put("target", "realm/" + realmId + "/account/" + accountId);
    claims.put("taskClient", actor.clientId());
    claims.put("taskSubject", actor.serviceSubject());
    claims.put("payloadDigest", digest("payload", materialized));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("nonce", UUID.randomUUID().toString().replace("-", ""));
    claims.put("iat", now);
    claims.put("exp", expiry);
    String encoded = encode(canonical(claims).getBytes(StandardCharsets.UTF_8));
    return encoded + "." + encode(mac("proof\n" + encoded));
  }

  /** No caller can construct a verified outcome from an HTTP status or public tuple. */
  static final class VerifiedSelfAppOutcome {
    private final int status;
    private final String receipt;
    private final JsonNode emailCredential;
    private final long emailExpiresAt;
    private final String emailStartOperation;

    private VerifiedSelfAppOutcome(int status, String receipt) {
      this(status, receipt, null, 0, null);
    }

    private VerifiedSelfAppOutcome(
        int status, String receipt, JsonNode credential, long expiry, String emailStartOperation) {
      this.status = status;
      this.receipt = receipt;
      this.emailCredential = credential == null ? null : credential.deepCopy();
      this.emailExpiresAt = expiry;
      this.emailStartOperation = emailStartOperation;
    }

    boolean belongsToEmailStart(String operation) {
      return emailStartOperation != null && emailStartOperation.equals(operation);
    }

    boolean sameEmailIdentity(VerifiedSelfAppOutcome other) {
      return other != null
          && emailCredential != null
          && other.emailCredential != null
          && emailCredential.path("credentialId").equals(other.emailCredential.path("credentialId"))
          && emailCredential
              .path("destinationDigest")
              .equals(other.emailCredential.path("destinationDigest"));
    }

    boolean sameEmailDelivery(VerifiedSelfAppOutcome other) {
      return other != null
          && emailCredential != null
          && emailCredential.equals(other.emailCredential)
          && emailExpiresAt == other.emailExpiresAt;
    }

    int status() {
      return status;
    }

    String receipt() {
      return receipt;
    }

    @Override
    public String toString() {
      return "VerifiedSelfAppOutcome[redacted]";
    }
  }

  VerifiedSelfAppOutcome verifySelfAppOtpReceipt(
      String reply, String realmId, TaskActorCredentials actor, JsonNode frozenCommand) {
    return verifySelfOtpReceipt(reply, realmId, actor, frozenCommand, false);
  }

  VerifiedSelfAppOutcome verifySelfDeleteOtpReceipt(
      String reply, String realmId, TaskActorCredentials actor, JsonNode frozenCommand) {
    return verifySelfOtpReceipt(reply, realmId, actor, frozenCommand, true);
  }

  VerifiedSelfAppOutcome verifySelfEmailStartOtpReceipt(
      String reply, String realmId, TaskActorCredentials actor, JsonNode frozenCommand) {
    try {
      if (reply == null
          || reply.length() > 16384
          || actor.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN)
        throw invalidSecretIntent();
      JsonNode result = read(reply, JsonNode.class);
      intentFields(
          result,
          java.util.Set.of(
              "operationId", "accountId", "status", "deliveryState", "result", "receipt"));
      JsonNode body = frozenCommand.path("body");
      JsonNode target = body.path("target");
      if (!intentUuid(body, "operationId").equals(intentUuid(result, "operationId"))
          || !intentUuid(target, "accountId").equals(intentUuid(result, "accountId"))
          || !result.path("status").isIntegralNumber()
          || !result.path("status").canConvertToInt()) throw invalidSecretIntent();
      int status = result.path("status").intValue();
      String delivery = intentText(result, "deliveryState", 16);
      if (!(status == 202 && "READY".equals(delivery))
          && !(status == 200 && "SENT".equals(delivery))) throw invalidSecretIntent();
      JsonNode nativeResult = result.path("result");
      intentFields(nativeResult, java.util.Set.of("info"));
      if (!(status == 202 ? "OTP mail pending" : "OTP mail sent")
          .equals(intentText(nativeResult, "info", 512))) throw invalidSecretIntent();
      String receipt = intentText(result, "receipt", 8192);
      String[] parts = receipt.split("\\.", -1);
      if (parts.length != 2 || !parts[1].matches("[a-f0-9]{64}")) throw invalidSecretIntent();
      byte[] payload = intentBase64(json.valueToTree(parts[0]), 1, 6000);
      if (!java.security.MessageDigest.isEqual(
          mac("otp-operation-receipt\n" + parts[0]), HexFormat.of().parseHex(parts[1])))
        throw invalidSecretIntent();
      JsonNode saved = read(utf8(payload), JsonNode.class);
      JsonNode credential = saved.path("emailCredential");
      intentFields(credential, java.util.Set.of("credentialId", "generation", "destinationDigest"));
      intentUuid(credential, "credentialId");
      intentDigest(credential, "generation");
      if (!intentDigest(credential, "destinationDigest")
          .equals(digest("otp-email-destination", body.path("otp").path("email"))))
        throw invalidSecretIntent();
      if (!saved.path("authorityValidUntil").isIntegralNumber()
          || !saved.path("authorityValidUntil").canConvertToLong()
          || saved.path("authorityValidUntil").longValue() <= 0
          || !body.path("authorityValidUntil").isIntegralNumber()
          || !body.path("authorityValidUntil").canConvertToLong()
          || saved.path("authorityValidUntil").longValue()
              > body.path("authorityValidUntil").longValue()) throw invalidSecretIntent();
      long expiry = saved.path("authorityValidUntil").longValue();
      var expected = json.createObjectNode();
      expected.put("realmId", realmId);
      expected.put("accountId", target.path("accountId").textValue());
      expected.put("operationId", body.path("operationId").textValue());
      expected.put("operation", "otp.email-start");
      expected.put("ownerClient", actor.clientId());
      expected.put("ownerSubject", actor.serviceSubject());
      var materialized = ((ObjectNode) body).deepCopy();
      materialized.put("username", intentText(frozenCommand, "username", 255));
      expected.put("intentDigest", digest("otp-intent", materialized));
      expected.put("previousState", intentDigest(body, "expectedState"));
      expected.set("tenantId", target.path("tenantId"));
      expected.put("originDigest", digest("otp-origin", target.path("origin")));
      expected.put("status", status);
      expected.put("deliveryState", delivery);
      expected.put("authorityValidUntil", expiry);
      expected.put("resultDigest", digest("otp-result", nativeResult));
      expected.set("emailCredential", credential);
      if (!saved.equals(read(canonical(expected), JsonNode.class))
          || !utf8(payload).equals(canonical(expected))) throw invalidSecretIntent();
      return new VerifiedSelfAppOutcome(
          status, receipt, credential, expiry, body.path("operationId").textValue());
    } catch (RuntimeException failure) {
      throw invalidSecretIntent();
    }
  }

  VerifiedSelfAppOutcome verifySelfLinkedEmailFinishOtpReceipt(
      String reply,
      String realmId,
      TaskActorCredentials actor,
      JsonNode frozenCommand,
      VerifiedSelfAppOutcome deliveredStart) {
    if (deliveredStart == null
        || deliveredStart.status() != 200
        || !deliveredStart.belongsToEmailStart(
            frozenCommand.path("body").path("emailStartOperationId").textValue()))
      throw invalidSecretIntent();
    return verifySelfOtpReceipt(reply, realmId, actor, frozenCommand, false, deliveredStart);
  }

  private VerifiedSelfAppOutcome verifySelfOtpReceipt(
      String reply,
      String realmId,
      TaskActorCredentials actor,
      JsonNode frozenCommand,
      boolean deletion) {
    return verifySelfOtpReceipt(reply, realmId, actor, frozenCommand, deletion, null);
  }

  private VerifiedSelfAppOutcome verifySelfOtpReceipt(
      String reply,
      String realmId,
      TaskActorCredentials actor,
      JsonNode frozenCommand,
      boolean deletion,
      VerifiedSelfAppOutcome deliveredStart) {
    boolean linkedFinish = deliveredStart != null;
    try {
      if (reply == null
          || reply.length() > 16384
          || actor.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_ADMIN)
        throw invalidSecretIntent();
      JsonNode result = read(reply, JsonNode.class);
      intentFields(
          result, java.util.Set.of("operationId", "accountId", "status", "result", "receipt"));
      JsonNode body = frozenCommand.path("body");
      JsonNode target = body.path("target");
      if (!intentUuid(body, "operationId").equals(intentUuid(result, "operationId"))
          || !intentUuid(target, "accountId").equals(intentUuid(result, "accountId"))
          || !result.path("status").isIntegralNumber()
          || !result.path("status").canConvertToInt()) throw invalidSecretIntent();
      int status = result.path("status").intValue();
      JsonNode nativeResult = result.path("result");
      if ((!linkedFinish && status == 200) || (!deletion && status == 201)) {
        intentFields(nativeResult, java.util.Set.of("info"));
        if (linkedFinish) {
          if (!"OTP setup created".equals(intentText(nativeResult, "info", 512)))
            throw invalidSecretIntent();
        } else intentText(nativeResult, "info", 512);
      } else if (!deletion && status == 401) {
        intentFields(nativeResult, java.util.Set.of("error", "error_description"));
        if (!"invalid_grant".equals(intentText(nativeResult, "error", 100)))
          throw invalidSecretIntent();
        intentText(nativeResult, "error_description", 512);
      } else throw invalidSecretIntent();
      String receipt = intentText(result, "receipt", 8192);
      String[] parts = receipt.split("\\.", -1);
      if (parts.length != 2 || !parts[1].matches("[a-f0-9]{64}")) throw invalidSecretIntent();
      byte[] payload = intentBase64(json.valueToTree(parts[0]), 1, 6000);
      if (!java.security.MessageDigest.isEqual(
          mac("otp-operation-receipt\n" + parts[0]), HexFormat.of().parseHex(parts[1])))
        throw invalidSecretIntent();
      JsonNode saved = read(utf8(payload), JsonNode.class);
      var expected = json.createObjectNode();
      expected.put("realmId", realmId);
      expected.put("accountId", target.path("accountId").textValue());
      expected.put("operationId", body.path("operationId").textValue());
      expected.put(
          "operation",
          deletion ? "otp.delete" : linkedFinish ? "otp.email-finish" : "otp.app-setup");
      expected.put("ownerClient", actor.clientId());
      expected.put("ownerSubject", actor.serviceSubject());
      var materialized = ((ObjectNode) body).deepCopy();
      materialized.put("username", intentText(frozenCommand, "username", 255));
      expected.put("intentDigest", digest("otp-intent", materialized));
      expected.put("previousState", intentDigest(body, "expectedState"));
      expected.set("tenantId", target.path("tenantId"));
      expected.put("originDigest", digest("otp-origin", target.path("origin")));
      expected.put("status", status);
      expected.put("resultDigest", digest("otp-result", nativeResult));
      if (linkedFinish) {
        expected.put("emailStartOperationId", intentUuid(body, "emailStartOperationId"));
        JsonNode credential = saved.path("emailCredential");
        intentFields(
            credential, java.util.Set.of("credentialId", "generation", "destinationDigest"));
        intentUuid(credential, "credentialId");
        intentDigest(credential, "generation");
        intentDigest(credential, "destinationDigest");
        expected.set("emailCredential", credential);
        var outcome =
            new VerifiedSelfAppOutcome(
                status, receipt, credential, 0, body.path("emailStartOperationId").textValue());
        if (!outcome.sameEmailIdentity(deliveredStart)) throw invalidSecretIntent();
        if (!saved.equals(expected) || !utf8(payload).equals(canonical(expected)))
          throw invalidSecretIntent();
        return outcome;
      }
      if (!saved.equals(expected) || !utf8(payload).equals(canonical(expected)))
        throw invalidSecretIntent();
      return new VerifiedSelfAppOutcome(status, receipt);
    } catch (RuntimeException failure) {
      throw invalidSecretIntent();
    }
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

  /** Canonical creator proof for the single closed, read-only username selector. */
  public String usernameAvailabilityProof(
      String realmName, String realmId, TaskActorCredentials actor, Object body) {
    if (actor.actor() != de.caritas.cob.userservice.api.config.auth.TaskActor.ACCOUNT_CREATE)
      throw new IllegalArgumentException("Wrong availability actor");
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new TreeMap<>();
    claims.put("iss", "userservice");
    claims.put("aud", "oriso-account-maintenance");
    claims.put("purpose", "ACCOUNT_AVAILABILITY");
    claims.put("operation", "account.username-availability");
    claims.put("method", "POST");
    claims.put("actor", actor.actor().name());
    claims.put("realm", realmName);
    claims.put("target", "realm/" + realmId + "/availability/username");
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
