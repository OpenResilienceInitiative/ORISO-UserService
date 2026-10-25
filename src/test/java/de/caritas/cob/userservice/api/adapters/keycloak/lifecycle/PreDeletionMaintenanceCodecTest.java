package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Fixed bridge vectors use independent JCA MACs and preserve the old proof selector set. */
class PreDeletionMaintenanceCodecTest {
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final String ACCOUNT = "81000000-0000-4000-8000-000000000011";
  private static final String OPERATION = "81000000-0000-4000-8000-000000000012";
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
  private final TaskActorCredentials actor =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_ADMIN,
          "backend-account-admin",
          "fixture-secret",
          "82000000-0000-4000-8000-000000000012");

  @Test
  void fixedSavedOnlySelectorRetainsExactFifteenProofClaimsAndIndependentMac() {
    JsonNode body = body();
    String proof =
        assertDoesNotThrow(
            () -> codec.preDeletionOutcomeProof("fixture", "realm-id", ACCOUNT, actor, body));
    String[] parts = proof.split("\\.", -1);
    assertArrayEquals(hmac("proof\n" + parts[0]), Base64.getUrlDecoder().decode(parts[1]));
    JsonNode claims =
        codec.read(
            new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
            JsonNode.class);
    assertEquals(
        Set.of(
            "iss",
            "aud",
            "purpose",
            "operation",
            "method",
            "actor",
            "realm",
            "target",
            "taskClient",
            "taskSubject",
            "payloadDigest",
            "jti",
            "nonce",
            "iat",
            "exp"),
        fields(claims));
    assertEquals("account.deactivate.outcome", claims.path("operation").textValue());
    assertEquals("oriso-account-maintenance", claims.path("aud").textValue());
    assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").textValue());
    assertEquals("POST", claims.path("method").textValue());
    assertEquals("realm/realm-id/account/" + ACCOUNT, claims.path("target").textValue());
    assertEquals(digest("payload", body), claims.path("payloadDigest").textValue());
    assertEquals(60, claims.path("exp").longValue() - claims.path("iat").longValue());
  }

  @Test
  void fixedSavedOnlyProofRefusesEveryForeignOriginShapeAndTargetBeforeMac() {
    for (String kind :
        List.of("HUMAN_ADMIN", "DELETION", "IMPORT", "PASSWORD_RESET", "INACTIVITY")) {
      ObjectNode body = body();
      ((ObjectNode) body.path("target").path("origin")).put("kind", kind);
      assertThrows(
          IllegalArgumentException.class,
          () -> codec.preDeletionOutcomeProof("fixture", "realm-id", ACCOUNT, actor, body));
    }
    ObjectNode foreign = body();
    ((ObjectNode) foreign.path("target").path("origin")).put("humanSubject", OPERATION);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.preDeletionOutcomeProof("fixture", "realm-id", ACCOUNT, actor, foreign));
    ObjectNode extra = body();
    extra.put("authorityValidUntil", 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.preDeletionOutcomeProof("fixture", "realm-id", ACCOUNT, actor, extra));
    ObjectNode changed = body();
    ((ObjectNode) changed.path("change")).put("enabled", false);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.preDeletionOutcomeProof("fixture", "realm-id", ACCOUNT, actor, changed));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.preDeletionOutcomeProof("fixture", "realm-id", OPERATION, actor, body()));
    var creator =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_CREATE, "backend-account-create", "fixture-secret", OPERATION);
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.preDeletionOutcomeProof("fixture", "realm-id", ACCOUNT, creator, body()));
  }

  @Test
  void newNamedSelectorDoesNotWidenTheExistingOrdinaryProofMethod() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.maintenanceProof(
                "deactivate.outcome", "fixture", "realm-id", ACCOUNT, actor, body()));
    for (String command :
        List.of(
            "read",
            "delete",
            "locale",
            "profile",
            "email",
            "ordinary-profile",
            "profile-transition",
            "deactivate",
            "reactivate",
            "self-password",
            "password-reset.read",
            "password-reset"))
      assertDoesNotThrow(
          () -> codec.maintenanceProof(command, "fixture", "realm-id", ACCOUNT, actor, body()));
  }

  @Test
  void namedUpdatedAliasChecksTheSameOriginalElevenTupleFieldsAndMac() {
    JsonNode body = body();
    String receipt = reply(body, "deactivate", "UPDATED");
    assertEquals(
        receipt,
        assertDoesNotThrow(
            () -> codec.verifyMaintenancePreDeletionReceipt(receipt, "realm-id", actor, body)));
    assertThrows(
        RuntimeException.class,
        () ->
            codec.verifyMaintenancePreDeletionReceipt(
                reply(body, "reactivate", "UPDATED"), "realm-id", actor, body));
    assertThrows(
        RuntimeException.class,
        () ->
            codec.verifyMaintenancePreDeletionReceipt(
                reply(body, "deactivate", "NO_EFFECT"), "realm-id", actor, body));
    ObjectNode foreign = body();
    foreign.put("operationId", UUID.randomUUID().toString());
    assertThrows(
        RuntimeException.class,
        () -> codec.verifyMaintenancePreDeletionReceipt(receipt, "realm-id", actor, foreign));
    assertThrows(
        RuntimeException.class,
        () ->
            codec.verifyMaintenancePreDeletionReceipt(
                receipt.replace("UPDATED", "FORGED"), "realm-id", actor, body));
  }

  private ObjectNode body() {
    var body = codec.read("{}", ObjectNode.class);
    var target = body.putObject("target");
    target.put("accountId", ACCOUNT);
    target.put("tenantId", "7");
    var origin = target.putObject("origin");
    origin.put("kind", "SELF");
    origin.put("workflowId", OPERATION);
    origin.put("humanSubject", ACCOUNT);
    origin.put("humanIssuer", "https://identity.test/realms/fixture");
    origin.put("humanClient", "app");
    origin.putNull("humanAuthority");
    body.put("operationId", OPERATION);
    body.put("expectedState", "a".repeat(64));
    body.putObject("change");
    return body;
  }

  private String reply(JsonNode body, String command, String result) {
    var payload = new TreeMap<String, Object>();
    payload.put("realmId", "realm-id");
    payload.put("accountId", ACCOUNT);
    payload.put("operationId", OPERATION);
    payload.put("command", command);
    payload.put("ownerClient", actor.clientId());
    payload.put("ownerSubject", actor.serviceSubject());
    payload.put("intentDigest", digest("maintenance-intent", body));
    payload.put("previousState", body.path("expectedState").textValue());
    payload.put("tenantId", "7");
    payload.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
    payload.put("result", result);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(payload).getBytes(StandardCharsets.UTF_8));
    String receipt =
        encoded + "." + HexFormat.of().formatHex(hmac("maintenance-operation-receipt\n" + encoded));
    return codec.canonical(
        Map.of(
            "operationId", OPERATION, "accountId", ACCOUNT, "result", result, "receipt", receipt));
  }

  private Set<String> fields(JsonNode value) {
    var result = new HashSet<String>();
    value.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private String digest(String domain, Object value) {
    return HexFormat.of().formatHex(hmac(domain + "\n" + codec.canonical(value)));
  }

  private byte[] hmac(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
