package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.port.out.HumanAdminMaintenanceTransport.FrozenProfile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredImportNoEffectTest {
  private final byte[] key = "i".repeat(32).getBytes(StandardCharsets.UTF_8);
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(key);
  private final TaskActorCredentials actor =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_ADMIN,
          "backend-account-admin",
          "fixture-secret",
          "84000000-0000-4000-8000-000000000001");
  private final UUID operation = UUID.fromString("72000000-0000-4000-8000-000000000001");
  private final String account = "71000000-0000-4000-8000-000000000001", request = "d".repeat(64);

  @ParameterizedTest
  @ValueSource(strings = {"SELF", "HUMAN_ADMIN", "IMPORT"})
  void authenticatedTerminalEvidenceAllowsEachFreshOwnerButNeverChangedTargetOrCommand(
      String currentKind) {
    var origin = new TreeMap<String, Object>();
    origin.put("kind", "IMPORT");
    origin.put("workflowId", operation.toString());
    for (String f : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority"))
      origin.put(f, null);
    var workflow =
        Map.of(
            "v",
            1,
            "kind",
            "IMPORT",
            "workId",
            operation.toString(),
            "sourceDigest",
            "a".repeat(64),
            "recordNumber",
            1,
            "rowDigest",
            "b".repeat(64),
            "requiredRoles",
            List.of("consultant"));
    var body =
        codec.read(
            codec.canonical(
                Map.of(
                    "target",
                    Map.of("accountId", account, "tenantId", "7", "origin", origin),
                    "workflow",
                    workflow,
                    "operationId",
                    operation.toString(),
                    "expectedState",
                    "c".repeat(64),
                    "change",
                    Map.of("group", "/human-roles/consultant"))),
            JsonNode.class);
    var frozen =
        new FrozenProfile(
            codec.canonical(body),
            codec.digest(
                "existing-consultant-import-command/v1",
                Map.of("requestDigest", request, "body", body)));
    String receipt = receipt(body);
    assertDoesNotThrow(
        () ->
            StoredMaintenanceNoEffect.verify(
                codec,
                "realm",
                actor,
                account,
                7L,
                currentKind,
                operation,
                request,
                frozen,
                receipt));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StoredMaintenanceNoEffect.verify(
                codec,
                "realm",
                actor,
                account,
                8L,
                currentKind,
                operation,
                request,
                frozen,
                receipt));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StoredMaintenanceNoEffect.verify(
                codec,
                "realm",
                actor,
                account,
                7L,
                currentKind,
                UUID.randomUUID(),
                request,
                frozen,
                receipt));
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("change"))
        .put("group", "/human-roles/other");
    var other =
        new FrozenProfile(
            codec.canonical(changed),
            codec.digest(
                "existing-consultant-import-command/v1",
                Map.of("requestDigest", request, "body", changed)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StoredMaintenanceNoEffect.verify(
                codec,
                "realm",
                actor,
                account,
                7L,
                currentKind,
                operation,
                request,
                other,
                receipt));
  }

  private String receipt(JsonNode body) {
    var value = new TreeMap<String, Object>();
    value.put("realmId", "realm");
    value.put("accountId", account);
    value.put("operationId", operation.toString());
    value.put("command", "ordinary-profile");
    value.put("ownerClient", actor.clientId());
    value.put("ownerSubject", actor.serviceSubject());
    value.put("intentDigest", mac("maintenance-intent\n" + codec.canonical(body)));
    value.put("previousState", body.path("expectedState").textValue());
    value.put("tenantId", "7");
    value.put(
        "originDigest",
        mac("maintenance-origin\n" + codec.canonical(body.path("target").path("origin"))));
    value.put("result", "NO_EFFECT");
    value.put("status", 403);
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codec.canonical(value).getBytes(StandardCharsets.UTF_8));
    return codec.canonical(
        Map.of(
            "operationId",
            operation.toString(),
            "accountId",
            account,
            "result",
            "NO_EFFECT",
            "status",
            403,
            "receipt",
            encoded + "." + mac("maintenance-no-effect-receipt/v1\n" + encoded)));
  }

  private String mac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }
}
