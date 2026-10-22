package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Fixed fourteen-field row binding; independent JCA MACs never manufacture a claimed work item. */
class ClaimedInactivityRowCodecTest {
  private static final byte[] KEY = "synthetic-claimed-inactivity-row-key".getBytes(StandardCharsets.UTF_8);
  private final ObjectMapper json = new ObjectMapper();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);

  @Test
  void actualBootstrapRevisionZeroAndEverySavedFieldHaveTheFixedIndependentBinding() throws Exception {
    ObjectNode row = row();
    String digest = assertDoesNotThrow(() -> codec.claimedInactivityRowDigest(row));
    assertEquals(independent(row), digest);
    assertTrue(digest.matches("[0-9a-f]{64}"));
    for (String field : List.of("workId", "identityId", "tenantId", "assignedMonths", "revision", "lastActivityMicros", "dueAtMicros", "attempts", "lastError", "claimedAtMicros")) {
      ObjectNode changed = row.deepCopy();
      switch (field) {
        case "workId", "identityId" -> changed.put(field, "71000000-0000-4000-8000-000000000007");
        case "tenantId" -> changed.put(field, "8");
        case "lastError" -> changed.put(field, "KEYCLOAK:FAILED");
        default -> changed.put(field, row.path(field).longValue() + 1);
      }
      assertNotEquals(digest, codec.claimedInactivityRowDigest(changed), field);
      assertEquals(independent(changed), codec.claimedInactivityRowDigest(changed), field);
    }
  }

  @Test
  void closedRowRejectsMissingExtraCoercedBoundsAndMismatchedActionOrStatus() throws Exception {
    ObjectNode valid = row();
    for (var fields = valid.fieldNames(); fields.hasNext();) {
      String field = fields.next();
      ObjectNode missing = valid.deepCopy(); missing.remove(field);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(missing), field);
    }
    ObjectNode extra = valid.deepCopy(); extra.put("password", "synthetic-forbidden");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(extra));
    for (String field : List.of("assignedMonths", "revision", "attempts", "lastActivityMicros", "dueAtMicros", "claimedAtMicros")) {
      for (String malformed : List.of("null", "\"1\"", "1.0", "-1", "9223372036854775808")) {
        ObjectNode changed = valid.deepCopy(); changed.set(field, json.readTree(malformed));
        assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(changed), field + malformed);
      }
    }
    for (String field : List.of("assignedMonths", "lastActivityMicros", "claimedAtMicros")) {
      ObjectNode changed = valid.deepCopy(); changed.put(field, 0);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(changed), field);
    }
    for (String field : List.of("assignedMonths", "attempts")) {
      ObjectNode overflow = valid.deepCopy(); overflow.put(field, 2147483648L);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(overflow), field);
    }
    ObjectNode excessiveError = valid.deepCopy(); excessiveError.put("lastError", "A".repeat(1025));
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(excessiveError));
    ObjectNode invalid = valid.deepCopy(); invalid.put("dueAtMicros", 1);
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(invalid));
    for (String field : List.of("identityId", "workId", "status", "action", "tenantId", "lastError")) {
      ObjectNode changed = valid.deepCopy(); changed.put(field, "invalid/secret\n");
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(changed), field);
    }
    ObjectNode restored = valid.deepCopy(); restored.put("action", "RESTORE"); restored.put("status", "REACTIVATING");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(restored));
    restored.put("suspensionOperationId", "71000000-0000-4000-8000-000000000009");
    assertEquals(independent(restored), codec.claimedInactivityRowDigest(restored));
  }

  @Test
  void humanInitiatorIsClosedAndEveryOriginalIdentityFieldRemainsBound() throws Exception {
    ObjectNode human = row();
    ObjectNode initiator = human.putObject("initiator");
    initiator.put("kind", "HUMAN_ADMIN");
    initiator.put("subject", "71000000-0000-4000-8000-000000000003");
    initiator.put("issuer", "https://auth.example.test/realms/test");
    initiator.put("client", "admin");
    assertEquals(independent(human), codec.claimedInactivityRowDigest(human));
    assertNotEquals(codec.claimedInactivityRowDigest(row()), codec.claimedInactivityRowDigest(human));
    for (String field : List.of("subject", "issuer", "client")) {
      ObjectNode missing = human.deepCopy(); ((ObjectNode) missing.path("initiator")).remove(field);
      assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(missing), field);
    }
    ObjectNode rawBearer = human.deepCopy(); ((ObjectNode) rawBearer.path("initiator")).put("bearer", "synthetic-forbidden");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(rawBearer));
    ObjectNode machineExtra = row(); ((ObjectNode) machineExtra.path("initiator")).put("subject", "71000000-0000-4000-8000-000000000003");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(machineExtra));
  }

  @Test
  void deletionRowUsesItsSeparateClosedFixedDomainAndCannotEnterTheRestoreSchema() throws Exception {
    ObjectNode deletion = row(); deletion.put("status", "DELETING"); deletion.put("action", "DELETE");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityRowDigest(deletion));
    assertEquals(independent(deletion, "claimed-inactivity-deletion-row/v1"), assertDoesNotThrow(() -> codec.claimedInactivityDeletionRowDigest(deletion)));
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityDeletionRowDigest(row()));
    ObjectNode wrongRef = deletion.deepCopy(); wrongRef.put("suspensionOperationId", "71000000-0000-4000-8000-000000000009");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityDeletionRowDigest(wrongRef));
    ObjectNode extra = deletion.deepCopy(); extra.put("extra", true);
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityDeletionRowDigest(extra));
    ObjectNode missing = deletion.deepCopy(); missing.remove("workId");
    assertThrows(IllegalArgumentException.class, () -> codec.claimedInactivityDeletionRowDigest(missing));
  }

  private ObjectNode row() throws Exception {
    return (ObjectNode) json.readTree("""
        {"identityId":"71000000-0000-4000-8000-000000000001","tenantId":"7","assignedMonths":24,
         "revision":0,"lastActivityMicros":1672531200123456,"dueAtMicros":1735689600123456,
         "status":"SUSPENDING","attempts":0,"lastError":null,
         "workId":"71000000-0000-4000-8000-000000000002","action":"SUSPEND",
         "initiator":{"kind":"SCHEDULED"},"suspensionOperationId":null,"claimedAtMicros":1769904000123456}
        """);
  }

  private String independent(JsonNode row) throws Exception {
    return independent(row, "claimed-inactivity-row/v1");
  }

  private String independent(JsonNode row, String domain) throws Exception {
    Object value = json.convertValue(row, Object.class);
    String canonical = json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal((domain + "\n" + canonical).getBytes(StandardCharsets.UTF_8)));
  }
}
