package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Fixed data MAC only; no JSON value can issue private claimed-work authority. */
class ClaimedInactivitySuspensionCustodyCodecTest {
  private static final String ACCOUNT = "61000000-0000-4000-8000-000000000001";
  private static final String WORK = "61000000-0000-4000-8000-000000000002";
  private static final String REALM = "61000000-0000-4000-8000-000000000003";
  private static final String OWNER = "61000000-0000-4000-8000-000000000004";
  private static final byte[] KEY = new byte[32];
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);

  @Test
  void capturedOriginalSourceAndOriginalAbsenceHaveTheFixedIndependentMac() throws Exception {
    for (boolean local : List.of(true, false)) {
      var value = custody("CAPTURED", local);
      String actual = assertDoesNotThrow(() -> codec.claimedInactivitySuspensionCustodyDigest(value));
      assertEquals(independent(value, "claimed-inactivity-suspension-custody/v1"), actual);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(value));
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityDeletionIntentDigest(value));
    }
  }

  @Test
  void frozenAndBothBoundTerminalShapesPreserveTheOriginalSourceAndOperation() throws Exception {
    for (String phase : List.of("FROZEN", "ACKNOWLEDGED", "NO_EFFECT")) {
      var value = custody(phase, true);
      String actual = assertDoesNotThrow(() -> codec.claimedInactivitySuspensionCustodyDigest(value));
      assertEquals(independent(value, "claimed-inactivity-suspension-custody/v1"), actual);
      assertEquals(WORK, value.path("nativeBody").path("operationId").textValue());
      assertEquals("2023-01-01T00:00:00.123456Z", value.path("source").path("user").get(0).path("create_date").textValue());
    }
  }

  @Test
  void sourceBirthAndMatrixIdentityChangeTheDigestWithoutCreatingAClaim() throws Exception {
    var original = custody("CAPTURED", true);
    String originalDigest = assertDoesNotThrow(() -> codec.claimedInactivitySuspensionCustodyDigest(original));
    for (String field : List.of("create_date", "matrix_user_id")) {
      var changed = original.deepCopy();
      ((ObjectNode) changed.path("source").path("user").get(0)).put(field,
          field.equals("create_date") ? "2023-01-02T00:00:00.123456Z" : "@different:fixture.invalid");
      String digest = assertDoesNotThrow(() -> codec.claimedInactivitySuspensionCustodyDigest(changed));
      assertNotEquals(originalDigest, digest);
      assertEquals(independent(changed, "claimed-inactivity-suspension-custody/v1"), digest);
    }
  }

  @Test
  void unknownPhasesCrossOperationExtraMissingAndUnboundedDataStayClosed() throws Exception {
    var valid = custody("FROZEN", true);
    for (String field : List.of("representation", "originalRow", "source", "nativeBody", "nativeReceipt", "phase")) {
      var missing = valid.deepCopy();
      missing.remove(field);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(missing));
    }
    var extra = valid.deepCopy();
    extra.put("bindingDigest", "a".repeat(64));
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(extra));
    var foreign = valid.deepCopy();
    ((ObjectNode) foreign.path("nativeBody")).put("operationId", ACCOUNT);
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(foreign));
    var wrong = valid.deepCopy();
    wrong.put("phase", "UNKNOWN");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(wrong));
    var secret = valid.deepCopy();
    ((ObjectNode) secret.path("source").path("user").get(0)).put("mobile_token", "forbidden");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(secret));
    var oversized = valid.deepCopy();
    ((ObjectNode) oversized.path("source").path("user").get(0)).put("username", "x".repeat(8193));
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(oversized));
    var fractional = valid.deepCopy();
    ((ObjectNode) fractional.path("originalRow")).put("revision", 0.5);
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivitySuspensionCustodyDigest(fractional));
  }

  private ObjectNode custody(String phase, boolean local) {
    var value = codec.read("{}", ObjectNode.class);
    value.put("representation", "CLAIMED_INACTIVITY_SUSPENSION_CUSTODY_V1");
    var row = value.putObject("originalRow");
    row.put("identityId", ACCOUNT);
    row.put("tenantId", "7");
    row.put("assignedMonths", 24);
    row.put("revision", 0);
    row.put("lastActivityMicros", 1672531200123456L);
    row.put("dueAtMicros", 1735689600123456L);
    row.put("status", "SUSPENDING");
    row.put("attempts", 0);
    row.putNull("lastError");
    row.put("workId", WORK);
    row.put("action", "SUSPEND");
    row.putObject("initiator").put("kind", "SCHEDULED");
    row.putNull("suspensionOperationId");
    row.put("claimedAtMicros", 1769904000123456L);
    var source = value.putObject("source");
    source.putArray("admin");
    source.putArray("consultant");
    var users = source.putArray("user");
    if (local) {
      var user = users.addObject();
      user.put("user_id", ACCOUNT);
      user.put("tenant_id", 7);
      user.put("username", "enc.synthetic-custody");
      user.put("create_date", "2023-01-01T00:00:00.123456Z");
      user.putNull("delete_date");
      user.put("deletion_lifecycle_state", "ACTIVE");
      user.put("matrix_user_id", "@owned:fixture.invalid");
    }
    value.putNull("nativeBody");
    value.putNull("nativeReceipt");
    value.put("phase", phase);
    if (!"CAPTURED".equals(phase)) {
      var body = value.putObject("nativeBody");
      var target = body.putObject("target");
      target.put("accountId", ACCOUNT);
      target.put("tenantId", "7");
      var origin = target.putObject("origin");
      origin.put("kind", "INACTIVITY");
      origin.put("workflowId", WORK);
      for (String field : List.of("humanSubject", "humanIssuer", "humanClient", "humanAuthority")) origin.putNull(field);
      var workflow = body.putObject("workflow");
      workflow.put("v", 1);
      workflow.put("kind", "INACTIVITY");
      workflow.put("workId", WORK);
      workflow.put("action", "SUSPEND");
      workflow.put("rowDigest", codec.claimedInactivityRowDigest(row));
      workflow.put("revision", 0);
      workflow.put("assignedMonths", 24);
      workflow.put("lastActivity", 1672531200L);
      workflow.put("dueAt", 1735689600L);
      workflow.putNull("suspensionOperationId");
      body.put("operationId", WORK);
      body.put("expectedState", "a".repeat(64));
      body.putObject("change");
      if (!"FROZEN".equals(phase)) value.put("nativeReceipt", nativeReceipt(body, "NO_EFFECT".equals(phase)));
    }
    return value;
  }

  private String nativeReceipt(JsonNode body, boolean refused) {
    var outcome = new TreeMap<String, Object>();
    outcome.put("realmId", REALM);
    outcome.put("accountId", ACCOUNT);
    outcome.put("operationId", WORK);
    outcome.put("command", "deactivate");
    outcome.put("ownerClient", "backend-account-admin");
    outcome.put("ownerSubject", OWNER);
    outcome.put("intentDigest", codec.digest("maintenance-intent", body));
    outcome.put("previousState", body.path("expectedState").textValue());
    outcome.put("tenantId", "7");
    outcome.put("originDigest", codec.digest("maintenance-origin", body.path("target").path("origin")));
    outcome.put("result", refused ? "NO_EFFECT" : "UPDATED");
    if (refused) outcome.put("status", 409);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
    String domain = refused ? "maintenance-no-effect-receipt/v1" : "maintenance-operation-receipt";
    String token = encoded + "." + mac(domain + "\n" + encoded);
    var reply = new TreeMap<String, Object>();
    reply.put("accountId", ACCOUNT);
    reply.put("operationId", WORK);
    reply.put("result", refused ? "NO_EFFECT" : "UPDATED");
    reply.put("receipt", token);
    if (refused) reply.put("status", 409);
    return codec.canonical(reply);
  }

  private String independent(JsonNode value, String domain) {
    return mac(domain + "\n" + codec.canonical(value));
  }

  private String mac(String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException unavailable) {
      throw new AssertionError(unavailable);
    }
  }
}
