package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Cross-receiver synthetic vector and independently signed mutations of every frozen binding. */
class MaintenanceNoEffectCodecTest {
  private final ObjectMapper json = new ObjectMapper();
  private final JsonNode vector;
  private final AccountLifecycleCodec codec;
  private final TaskActorCredentials actor;
  private final byte[] key;

  MaintenanceNoEffectCodecTest() throws Exception {
    try (var input = getClass().getResourceAsStream("/maintenance-no-effect-vector-v1.json")) {
      vector = json.readTree(input);
    }
    key = HexFormat.of().parseHex(vector.path("testKeyHex").textValue());
    codec = new AccountLifecycleCodec(key);
    actor =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_ADMIN,
            vector.path("payload").path("ownerClient").textValue(),
            "synthetic-only",
            vector.path("payload").path("ownerSubject").textValue());
  }

  @Test
  void receiverVectorAndBothDefiniteStatusesProduceOnlyVerifiedBoundOutcomes() throws Exception {
    JsonNode body = vector.path("requestBody");
    String original = json.writeValueAsString(vector.path("outer"));
    var verified = assertDoesNotThrow(() -> verify(original, body));
    assertEquals(409, verified.status());
    assertEquals(vector.path("outer").path("receipt").textValue(), verified.receipt());
    assertEquals(UUID.fromString(body.path("operationId").textValue()), verified.operationId());
    assertEquals(body.path("target").path("accountId").textValue(), verified.accountId());
    assertEquals("profile", verified.command());
    assertEquals(vector.path("payload").path("realmId").textValue(), verified.realmId());
    assertEquals(vector.path("payload").path("intentDigest").textValue(), verified.intentDigest());
    ObjectNode forbidden = vector.path("payload").deepCopy();
    forbidden.put("status", 403);
    assertEquals(403, verify(reply(forbidden), body).status());
    ObjectNode policy = vector.path("payload").deepCopy();
    policy.put("status", 400);
    policy.put("command", "password-reset");
    assertEquals(
        400,
        codec
            .verifyMaintenanceNoEffectReceipt(
                reply(policy),
                vector.path("payload").path("realmId").textValue(),
                actor,
                body,
                "password-reset")
            .status());
  }

  @Test
  void everyResignedNativeBindingAndEveryChangedFrozenIntentIsRejected() throws Exception {
    JsonNode body = vector.path("requestBody");
    for (var fields = vector.path("payload").fieldNames(); fields.hasNext(); ) {
      String field = fields.next();
      if (field.equals("status")) continue;
      ObjectNode changed = vector.path("payload").deepCopy();
      changed.put(
          field,
          field.endsWith("Digest") || field.equals("previousState")
              ? "b".repeat(64)
              : field.equals("operationId") ? "77777777-7777-4777-8777-777777777777" : "foreign");
      String resigned = reply(changed);
      assertThrows(IllegalArgumentException.class, () -> verify(resigned, body), field);
    }
    String valid = json.writeValueAsString(vector.path("outer"));
    for (String field : List.of("operationId", "expectedState", "change", "target")) {
      ObjectNode changed = body.deepCopy();
      if (field.equals("change"))
        ((ObjectNode) changed.path(field)).put("email", "other@example.test");
      else if (field.equals("target")) ((ObjectNode) changed.path(field)).put("tenantId", "12");
      else
        changed.put(
            field,
            field.equals("operationId") ? "77777777-7777-4777-8777-777777777777" : "b".repeat(64));
      assertThrows(IllegalArgumentException.class, () -> verify(valid, changed), field);
    }
  }

  @Test
  void closedOuterAndSignedShapesAndIntegralStatusCannotBeCoerced() throws Exception {
    JsonNode body = vector.path("requestBody");
    ObjectNode extra = vector.path("payload").deepCopy();
    extra.put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> verify(reply(extra), body));
    for (String field : List.of("status", "ownerSubject", "tenantId")) {
      ObjectNode missing = vector.path("payload").deepCopy();
      missing.remove(field);
      assertThrows(IllegalArgumentException.class, () -> verify(reply(missing), body));
    }
    for (JsonNode value :
        List.of(
            json.readTree("409.0"),
            json.readTree("\"409\""),
            json.readTree("null"),
            json.readTree("400"),
            json.readTree("401"),
            json.readTree("500"))) {
      ObjectNode changed = vector.path("payload").deepCopy();
      changed.set("status", value);
      assertThrows(IllegalArgumentException.class, () -> verify(reply(changed), body));
    }
    ObjectNode outer = vector.path("outer").deepCopy();
    outer.put("status", 403);
    assertThrows(
        IllegalArgumentException.class, () -> verify(json.writeValueAsString(outer), body));
    outer.put("status", 409);
    outer.put("extra", true);
    assertThrows(
        IllegalArgumentException.class, () -> verify(json.writeValueAsString(outer), body));
    String original = json.writeValueAsString(vector.path("outer"));
    assertThrows(IllegalArgumentException.class, () -> verify(original + " {}", body));
    assertThrows(
        IllegalArgumentException.class,
        () -> verify(original.replaceFirst("\\{", "{\"status\":409,"), body));
  }

  @Test
  void wrongMacDomainActorAndRequestedCommandNeverCreateAnAcknowledgement() throws Exception {
    JsonNode body = vector.path("requestBody");
    ObjectNode outer = vector.path("outer").deepCopy();
    String payload = outer.path("receipt").textValue().split("\\.")[0];
    outer.put("receipt", payload + "." + hmac("maintenance-operation-receipt\n" + payload));
    assertThrows(
        IllegalArgumentException.class, () -> verify(json.writeValueAsString(outer), body));
    String valid = json.writeValueAsString(vector.path("outer"));
    String realm = vector.path("payload").path("realmId").textValue();
    var creator =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_CREATE, actor.clientId(), "synthetic-only", actor.serviceSubject());
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyMaintenanceNoEffectReceipt(valid, realm, creator, body, "profile"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyMaintenanceNoEffectReceipt(valid, realm, actor, body, "email"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.verifyMaintenanceNoEffectReceipt(valid, "foreign", actor, body, "profile"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.verifyMaintenanceNoEffectReceipt(
                "{\"status\":409}", realm, actor, body, "profile"));
  }

  @Test
  void chosenPasswordReactivationPolicy400RequiresTheExactThreeTextualFieldsWithoutWorkflow()
      throws Exception {
    ObjectNode body = vector.path("requestBody").deepCopy();
    body.set(
        "change",
        json.createObjectNode()
            .put("expectedUsername", "exact-native-asker")
            .put("expectedEmail", "native@fixture.invalid")
            .put("password", "synthetic-policy-rejected"));
    ObjectNode outcome = vector.path("payload").deepCopy();
    outcome.put("command", "reactivate");
    outcome.put("status", 400);
    outcome.put("intentDigest", hmac("maintenance-intent\n" + codec.canonical(body)));
    String realm = vector.path("payload").path("realmId").textValue();
    var verified =
        assertDoesNotThrow(
            () ->
                codec.verifyMaintenanceNoEffectReceipt(
                    reply(outcome), realm, actor, body, "reactivate"));
    assertEquals(400, verified.status());
    assertEquals("reactivate", verified.command());
    for (String scenario :
        List.of(
            "workflow",
            "extra-body",
            "extra-change",
            "missing-field",
            "nontext-username",
            "nontext-email",
            "nontext-password",
            "empty-password",
            "oversized-password",
            "restore")) {
      ObjectNode changed = body.deepCopy();
      ObjectNode change = (ObjectNode) changed.path("change");
      switch (scenario) {
        case "workflow" ->
            changed.set("workflow", json.createObjectNode().put("kind", "INACTIVITY"));
        case "extra-body" -> changed.put("authorityValidUntil", 32503680000L);
        case "extra-change" -> change.put("temporary", false);
        case "missing-field" -> change.remove("expectedEmail");
        case "nontext-username" -> change.put("expectedUsername", 17);
        case "nontext-email" -> change.put("expectedEmail", 17);
        case "nontext-password" -> change.put("password", 17);
        case "empty-password" -> change.put("password", "");
        case "oversized-password" -> change.put("password", "x".repeat(4097));
        case "restore" -> {
          changed.set("change", json.createObjectNode().put("restoreEnabled", true));
          changed.set(
              "workflow",
              json.createObjectNode()
                  .put("v", 1)
                  .put("kind", "INACTIVITY")
                  .put("workId", "77777777-7777-4777-8777-777777777777")
                  .put("action", "RESTORE")
                  .put("rowDigest", "b".repeat(64))
                  .put("revision", 0)
                  .put("assignedMonths", 3)
                  .put("lastActivity", 1700000000L)
                  .put("dueAt", 1710000000L)
                  .put("suspensionOperationId", "88888888-8888-4888-8888-888888888888"));
        }
      }
      ObjectNode resigned = outcome.deepCopy();
      resigned.put("intentDigest", hmac("maintenance-intent\n" + codec.canonical(changed)));
      String proof = reply(resigned);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.verifyMaintenanceNoEffectReceipt(proof, realm, actor, changed, "reactivate"),
          scenario);
    }
  }

  private AccountLifecycleCodec.VerifiedMaintenanceNoEffect verify(String reply, JsonNode body) {
    return codec.verifyMaintenanceNoEffectReceipt(
        reply, vector.path("payload").path("realmId").textValue(), actor, body, "profile");
  }

  private String reply(ObjectNode outcome) throws Exception {
    // Independent signing must retain malformed numeric representations so they reach the verifier.
    var sorted = new TreeMap<String, Object>();
    outcome
        .fields()
        .forEachRemaining(
            entry -> sorted.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.writeValueAsString(sorted).getBytes(StandardCharsets.UTF_8));
    ObjectNode outer = json.createObjectNode();
    for (String field : List.of("operationId", "accountId", "result", "status"))
      outer.set(field, outcome.path(field));
    outer.put("receipt", payload + "." + hmac("maintenance-no-effect-receipt/v1\n" + payload));
    return json.writeValueAsString(outer);
  }

  private String hmac(String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }
}
