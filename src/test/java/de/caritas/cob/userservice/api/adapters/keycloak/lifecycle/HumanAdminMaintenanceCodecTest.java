package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Independent JCA MACs and immutable wire assertions; no native authority inferred. */
class HumanAdminMaintenanceCodecTest {
  private static final byte[] KEY =
      "synthetic-admin-codec-independent-key".getBytes(StandardCharsets.UTF_8);
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
  private final ObjectMapper json = new ObjectMapper();
  private final TaskActorCredentials actor =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_ADMIN,
          "backend-account-admin",
          "synthetic-only",
          "native-admin-subject");

  @Test
  void onlyFixedProfileEmailProofsKeepTheOriginalFifteenClaimsAndIndependentMac() throws Exception {
    for (String command : List.of("profile", "email")) {
      JsonNode body =
          json.readTree(
              "{\"target\":{\"accountId\":\"native-account\",\"tenantId\":\"7\",\"origin\":{\"kind\":\"HUMAN_ADMIN\"}},\"change\":{\"email\":\"äda@example.test\"}}");
      String proof =
          assertDoesNotThrow(
              () ->
                  codec.maintenanceProof(
                      command, "test", "realm-1", "native-account", actor, body));
      String[] parts = proof.split("\\.", -1);
      assertEquals(2, parts.length);
      assertArrayEquals(hmac("proof\n" + parts[0]), Base64.getUrlDecoder().decode(parts[1]));
      JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      var fields = new HashSet<String>();
      claims.fieldNames().forEachRemaining(fields::add);
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
          fields);
      assertEquals("userservice", claims.path("iss").textValue());
      assertEquals("oriso-account-maintenance", claims.path("aud").textValue());
      assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").textValue());
      assertEquals("account." + command, claims.path("operation").textValue());
      assertEquals("POST", claims.path("method").textValue());
      assertEquals("ACCOUNT_ADMIN", claims.path("actor").textValue());
      assertEquals("test", claims.path("realm").textValue());
      assertEquals("realm/realm-1/account/native-account", claims.path("target").textValue());
      assertEquals(actor.clientId(), claims.path("taskClient").textValue());
      assertEquals(actor.serviceSubject(), claims.path("taskSubject").textValue());
      assertEquals(
          HexFormat.of().formatHex(hmac("payload\n" + codec.canonical(body))),
          claims.path("payloadDigest").textValue());
      assertEquals(60, claims.path("exp").longValue() - claims.path("iat").longValue());
    }
    for (String command :
        List.of("password", "create", "deactivate", "reactivate", "ordinary-profile", "unknown"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              codec.maintenanceProof(
                  command, "test", "realm-1", "native-account", actor, Map.of()));
    var creator =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_CREATE,
            "backend-account-create",
            "synthetic-only",
            "creator-subject");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.maintenanceProof(
                "profile", "test", "realm-1", "native-account", creator, Map.of()));
  }

  @Test
  void fixedProfileEmailReceiptsVerifyEveryOriginalBindingAndRejectResignedMutation()
      throws Exception {
    for (String command : List.of("profile", "email")) {
      JsonNode body =
          json.readTree(
              "{\"target\":{\"accountId\":\"native-account\",\"tenantId\":\"7\",\"origin\":{\"kind\":\"HUMAN_ADMIN\",\"workflowId\":\"operation\"}},\"operationId\":\"60000000-0000-4000-8000-000000000003\",\"expectedState\":\""
                  + "a".repeat(64)
                  + "\",\"change\":{\"email\":\"äda@example.test\"}}");
      Map<String, Object> outcome = new TreeMap<>();
      outcome.put("realmId", "realm-1");
      outcome.put("accountId", "native-account");
      outcome.put("operationId", body.path("operationId").textValue());
      outcome.put("command", command);
      outcome.put("ownerClient", actor.clientId());
      outcome.put("ownerSubject", actor.serviceSubject());
      outcome.put("intentDigest", digest("maintenance-intent", body));
      outcome.put("previousState", body.path("expectedState").textValue());
      outcome.put("tenantId", "7");
      outcome.put("originDigest", digest("maintenance-origin", body.path("target").path("origin")));
      outcome.put("result", "UPDATED");
      String valid = reply(outcome);
      assertEquals(valid, assertDoesNotThrow(() -> verify(command, valid, body)));
      for (String field : outcome.keySet()) {
        var changed = new TreeMap<>(outcome);
        changed.put(
            field,
            field.equals("operationId")
                ? "60000000-0000-4000-8000-000000000099"
                : field.endsWith("Digest") || field.equals("previousState")
                    ? "b".repeat(64)
                    : "foreign");
        String resigned = reply(changed);
        assertThrows(IllegalArgumentException.class, () -> verify(command, resigned, body), field);
      }
      var extra = new TreeMap<>(outcome);
      extra.put("extra", true);
      assertThrows(IllegalArgumentException.class, () -> verify(command, reply(extra), body));
      assertThrows(
          IllegalArgumentException.class,
          () -> verify(command, valid.replace("UPDATED", "OTHER"), body));
      var otherBody = body.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) otherBody.path("change"))
          .put("email", "foreign@example.test");
      assertThrows(IllegalArgumentException.class, () -> verify(command, valid, otherBody));
    }
  }

  private String verify(String command, String reply, JsonNode frozen) {
    return command.equals("profile")
        ? codec.verifyMaintenanceProfileReceipt(reply, "realm-1", actor, frozen)
        : codec.verifyMaintenanceEmailReceipt(reply, "realm-1", actor, frozen);
  }

  private String reply(Map<String, Object> outcome) throws Exception {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
    return codec.canonical(
        Map.of(
            "operationId",
            outcome.get("operationId"),
            "accountId",
            outcome.get("accountId"),
            "result",
            outcome.get("result"),
            "receipt",
            encoded
                + "."
                + HexFormat.of().formatHex(hmac("maintenance-operation-receipt\n" + encoded))));
  }

  private String digest(String domain, Object body) throws Exception {
    return HexFormat.of().formatHex(hmac(domain + "\n" + codec.canonical(body)));
  }

  private byte[] hmac(String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }
}
