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

/** Fixed source custody only; synthetic digests do not construct live import capabilities. */
class ExistingConsultantImportCodecTest {
  private static final String ACCOUNT = "82000000-0000-4000-8000-000000000001";
  private static final byte[] KEY =
      "synthetic-existing-import-key-32bytes".getBytes(StandardCharsets.UTF_8);
  private final ObjectMapper json = new ObjectMapper();
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);

  @Test
  void exactCapturedSourceTupleHasIndependentMacAndNoWorkIdOrIncarnationRefresh() throws Exception {
    var source = source();
    String digest = assertDoesNotThrow(() -> codec.existingImportSourceKey(source));
    assertEquals(independent("existing-consultant-import-source/v1", source), digest);
    for (String field : List.of("sourceDigest", "recordNumber", "accountId", "tenantId")) {
      var changed = source.deepCopy();
      switch (field) {
        case "sourceDigest" -> changed.put(field, "b".repeat(64));
        case "recordNumber" -> changed.put(field, 2L);
        case "accountId" -> changed.put(field, "82000000-0000-4000-8000-000000000002");
        case "tenantId" -> changed.put(field, "8");
      }
      assertNotEquals(digest, codec.existingImportSourceKey(changed));
      assertEquals(
          independent("existing-consultant-import-source/v1", changed),
          codec.existingImportSourceKey(changed));
      var missing = source.deepCopy();
      missing.remove(field);
      assertThrows(IllegalArgumentException.class, () -> codec.existingImportSourceKey(missing));
    }
    for (String extra : List.of("workId", "createdAtMicros", "purpose", "password")) {
      var changed = source.deepCopy();
      changed.put(extra, "synthetic-forbidden");
      assertThrows(IllegalArgumentException.class, () -> codec.existingImportSourceKey(changed));
    }
    for (String value : List.of("0", "-1", "1.0", "\"1\"", "9223372036854775808", "null")) {
      var changed = source.deepCopy();
      changed.set("recordNumber", json.readTree(value));
      assertThrows(IllegalArgumentException.class, () -> codec.existingImportSourceKey(changed));
    }
  }

  @Test
  void completeOriginalTwelveFieldRowBindsEveryNonsecretRecordAndResolvedAgency() throws Exception {
    var row = row();
    String digest = assertDoesNotThrow(() -> codec.existingImportRowDigest(row));
    assertEquals(12, row.size());
    assertEquals(14, row.path("record").size());
    assertEquals(independent("existing-consultant-import-row/v1", row), digest);
    var changed = row.deepCopy();
    changed.put("workId", "82000000-0000-4000-8000-000000000003");
    assertNotEquals(digest, codec.existingImportRowDigest(changed));
    changed = row.deepCopy();
    changed.put("createdAtMicros", 1760000000000001L);
    ((com.fasterxml.jackson.databind.node.ArrayNode)changed.path("originalSql").path("consultant"))
        .set(0,json.getNodeFactory().textNode(changed.path("originalSql").path("consultant").get(0).textValue()
            .replace("2025-10-09T08:53:20Z","2025-10-09T08:53:20.000001Z")));
    assertNotEquals(digest, codec.existingImportRowDigest(changed));
    var inconsistentIncarnation=row.deepCopy();inconsistentIncarnation.put("createdAtMicros",1760000000000001L);
    assertThrows(IllegalArgumentException.class,() -> codec.existingImportRowDigest(inconsistentIncarnation));
    changed = row.deepCopy();
    ((ObjectNode) changed.path("record")).put("absenceMessage", "Original message changed");
    assertNotEquals(digest, codec.existingImportRowDigest(changed));
    changed = row.deepCopy();
    changed.set(
        "initiator",
        json.valueToTree(
            Map.of(
                "kind",
                "HUMAN_ADMIN",
                "subject",
                "82000000-0000-4000-8000-000000000004",
                "issuer",
                "https://public-auth.example.test/realms/test",
                "client",
                "admin")));
    var human = changed;
    assertEquals(
        independent("existing-consultant-import-row/v1", human),
        assertDoesNotThrow(() -> codec.existingImportRowDigest(human)));
    for (String field : iterable(row.fieldNames())) {
      var missing = row.deepCopy();
      missing.remove(field);
      assertThrows(
          IllegalArgumentException.class, () -> codec.existingImportRowDigest(missing), field);
    }
    for (String field : iterable(row.path("record").fieldNames())) {
      var missing = row.deepCopy();
      ((ObjectNode) missing.path("record")).remove(field);
      assertThrows(
          IllegalArgumentException.class, () -> codec.existingImportRowDigest(missing), field);
    }
    for (String at : List.of("", "/record", "/initiator")) {
      var extra = row.deepCopy();
      var object = at.isEmpty() ? extra : (ObjectNode) extra.at(at);
      object.put("rawBearer", "synthetic-forbidden");
      assertThrows(IllegalArgumentException.class, () -> codec.existingImportRowDigest(extra));
    }
    for (String roles :
        List.of(
            "[]",
            "[\"consultant\",\"consultant\"]",
            "[\"group-chat-consultant\",\"consultant\"]",
            "[\"consultant\",\"realm-admin\"]",
            "[\"user\"]")) {
      var invalid = row.deepCopy();
      ((ObjectNode) invalid.path("record")).set("roles", json.readTree(roles));
      assertThrows(IllegalArgumentException.class, () -> codec.existingImportRowDigest(invalid));
    }
    var wrongTenant = row.deepCopy();
    ((ObjectNode) wrongTenant.path("record")).put("tenantId", 8L);
    assertThrows(IllegalArgumentException.class, () -> codec.existingImportRowDigest(wrongTenant));
    var duplicate = row.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("agencies"))
        .add(duplicate.path("agencies").get(0).deepCopy());
    assertThrows(IllegalArgumentException.class, () -> codec.existingImportRowDigest(duplicate));
  }

  private ObjectNode source() {
    return json.valueToTree(
        Map.of(
            "sourceDigest",
            "a".repeat(64),
            "recordNumber",
            1L,
            "accountId",
            ACCOUNT,
            "tenantId",
            "7"));
  }

  private ObjectNode row() {
    var source = source();
    source.put("v", 1);
    source.put("kind", "EXISTING_CONSULTANT_IMPORT");
    source.put("workId", "82000000-0000-4000-8000-000000000005");
    source.put("createdAtMicros", 1760000000000000L);
    var record = new TreeMap<String, Object>();
    record.put("tenantId", 7L);
    record.put("username", "existing-consultant");
    record.put("email", "existing@example.test");
    record.put("firstName", "Existing");
    record.put("lastName", "Consultant");
    record.put("consultantId", ACCOUNT);
    record.put("oldId", null);
    record.put("usernameEncoded", "enc.synthetic");
    record.put("agenciesAndRoleSets", "11;consultant");
    record.put("roles", List.of("consultant", "group-chat-consultant"));
    record.put("absent", false);
    record.put("absenceMessage", null);
    record.put("formalLanguage", true);
    record.put("teamConsultant", false);
    source.set("record", json.valueToTree(record));
    source.set(
        "agencies",
        json.valueToTree(
            List.of(
                Map.of(
                    "agencyId",
                    "11",
                    "requiredRoles",
                    List.of("consultant", "group-chat-consultant")))));
    source.set(
        "originalSql",
        json.valueToTree(
            Map.of(
                "admin",
                List.of(),
                "consultant",
                List.of(
                    "{\"consultant_id\":\""
                        + ACCOUNT
                        + "\",\"tenant_id\":7,\"create_date\":\"2025-10-09T08:53:20Z\"}"),
                "language",
                List.of(),
                "consultant_topic",
                List.of(),
                "consultant_agency",
                List.of(),
                "admin_agency",
                List.of())));
    source.set("initiator", json.valueToTree(Map.of("kind", "IMPORT")));
    return source;
  }

  private static List<String> iterable(Iterator<String> fields) {
    var names = new ArrayList<String>();
    fields.forEachRemaining(names::add);
    return names;
  }

  private String independent(String domain, JsonNode value) throws Exception {
    var writer =
        new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    String canonical = writer.writeValueAsString(writer.convertValue(value, Object.class));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((domain + "\n" + canonical).getBytes(StandardCharsets.UTF_8)));
  }
}
