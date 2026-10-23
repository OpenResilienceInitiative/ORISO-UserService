package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewedMaintenanceProfilesTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REALM = "22222222-2222-4222-8222-222222222222";

  private final Map<String, Set<String>> groups =
      Map.of(
          "/human-roles/user", Set.of("user"),
          "/human-roles/consultant", Set.of("consultant"),
          "/human-roles/chat", Set.of("consultant", "group-chat-consultant"),
          "/human-roles/agency", Set.of("restricted-agency-admin", "user-admin"),
          "/human-roles/combined", Set.of("consultant", "restricted-agency-admin", "user-admin"));

  private ObjectNode ordinary() {
    var root = JSON.createObjectNode().put("version", 1);
    root.putObject("native");
    var profiles = root.putArray("ordinaryProfiles");
    new TreeMap<>(groups).forEach((path, roles) -> {
      var profile = profiles.addObject();
      profile.put("id", path.substring("/human-roles/".length())).put("version", 1);
      profile.put("groupId", "group-" + profile.path("id").asText());
      profile.put("groupParentId", "human-root").put("groupPath", path);
      profile.set("realmRoles", JSON.valueToTree(new TreeSet<>(roles)));
      profile.putObject("clientRoles");
      profile.put("allowUnassignedTenant", false);
      profile.putArray("selfOperations").add("account.password").add("account.read");
    });
    return root;
  }

  private ObjectNode protectedPolicy() {
    var root = JSON.createObjectNode().put("realmId", REALM).put("realmName", "fixture");
    root.putArray("technicalRoles").add("technical");
    var profiles = root.putArray("profiles");
    for (var entry : Map.of(
        "tenant", Set.of("tenant-admin"),
        "tenant-consultant", Set.of("tenant-admin", "consultant"),
        "platform", Set.of("agency-admin", "tenant-admin")).entrySet()) {
      var profile = profiles.addObject();
      profile.put("id", entry.getKey()).put("version", Long.MAX_VALUE);
      profile.put("kind", entry.getKey().equals("platform") ? "PLATFORM_ADMIN" : "TENANT_ADMIN");
      profile.putArray("tenantModes").add("EXISTING");
      profile.put("groupId", "group-" + entry.getKey());
      profile.put("groupPath", "/human-roles/" + entry.getKey());
      profile.set("realmRoles", JSON.valueToTree(new TreeSet<>(entry.getValue())));
      profile.putObject("clientRoles");
      profile.put("usernamePolicy", "USERNAME_TRANSCODER_V1");
      profile.put("firstNamePolicy", "INVITE_WITH_EMAIL_LOCAL_PART_FALLBACK");
      profile.put("lastNamePolicy", "INVITE_WITH_ADMIN_FALLBACK");
    }
    return root;
  }

  private Map<String, String> initial() {
    return Map.of("TENANT_ADMIN", "tenant", "PLATFORM_ADMIN", "platform");
  }

  private ReviewedMaintenanceProfiles read(JsonNode ordinary, JsonNode protectedPolicy) {
    return ReviewedMaintenanceProfiles.read(
        ordinary.toString(), protectedPolicy.toString(), groups, initial(), REALM, "fixture");
  }

  @Test
  void readsTheSameInstalledSnapshotsForAllFiveSqlKinds() {
    var found = assertDoesNotThrow(() -> read(ordinary(), protectedPolicy())).profiles();
    assertEquals(Set.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN"), found.keySet());
    assertEquals(List.of("user"), found.get("USER").stream().map(ReviewedMaintenanceProfiles.Profile::id).toList());
    assertEquals(Set.of("consultant", "chat"), new HashSet<>(found.get("CONSULTANT").stream().map(ReviewedMaintenanceProfiles.Profile::id).toList()));
    assertEquals(Set.of("agency", "combined"), new HashSet<>(found.get("AGENCY_ADMIN").stream().map(ReviewedMaintenanceProfiles.Profile::id).toList()));
    assertTrue(found.get("CONSULTANT").stream().allMatch(profile -> profile.family().equals("ORDINARY")));
  }

  @Test
  void protectedSelectorsKeepTheExplicitLongVersionAndCompleteDualRoles() {
    var found = assertDoesNotThrow(() -> read(ordinary(), protectedPolicy())).profiles();
    assertEquals(2, found.get("TENANT_ADMIN").size());
    var dual = found.get("TENANT_ADMIN").stream().filter(profile -> profile.id().equals("tenant-consultant")).findFirst().orElseThrow();
    assertEquals("PROTECTED", dual.family());
    assertEquals(Long.MAX_VALUE, dual.version());
    assertEquals(Set.of("consultant", "tenant-admin"), dual.realmRoles());
  }

  @Test
  void initialKindWrongRealmAndMissingOrdinaryMapRefuse() {
    assertThrows(IllegalStateException.class, () -> ReviewedMaintenanceProfiles.read(
        ordinary().toString(), protectedPolicy().toString(), groups,
        Map.of("TENANT_ADMIN", "platform", "PLATFORM_ADMIN", "tenant"), REALM, "fixture"));
    assertThrows(IllegalStateException.class, () -> ReviewedMaintenanceProfiles.read(
        ordinary().toString(), protectedPolicy().toString(), Map.of("/human-roles/user", Set.of("user")), initial(), REALM, "fixture"));
    var foreign = protectedPolicy().put("realmName", "foreign");
    assertThrows(IllegalStateException.class, () -> read(ordinary(), foreign));
  }

  @Test
  void missingNonIntegralOverflowAndDuplicateVersionsOrRolesRefuse() {
    for (JsonNode version : List.of(JSON.getNodeFactory().nullNode(), JSON.getNodeFactory().textNode("1"),
        JSON.getNodeFactory().numberNode(1.0), JSON.getNodeFactory().numberNode(0),
        JSON.getNodeFactory().numberNode(new java.math.BigInteger("9223372036854775808")))) {
      var invalid = ordinary();
      ((ObjectNode) invalid.path("ordinaryProfiles").get(0)).set("version", version);
      assertThrows(IllegalStateException.class, () -> read(invalid, protectedPolicy()));
    }
    var repeated = ordinary();
    ((com.fasterxml.jackson.databind.node.ArrayNode) repeated.path("ordinaryProfiles")).add(repeated.path("ordinaryProfiles").get(0).deepCopy());
    assertThrows(IllegalStateException.class, () -> read(repeated, protectedPolicy()));
    var technical = protectedPolicy();
    ((ObjectNode) technical.path("profiles").get(0)).putArray("realmRoles").add("technical");
    assertThrows(IllegalStateException.class, () -> read(ordinary(), technical));
  }

  @Test
  void duplicateTrailingMissingOrPathShapedJsonRefuses() {
    var raw = ordinary().toString();
    for (String invalid : List.of(raw + "{}", raw.replaceFirst("\\\"version\\\":1", "\"version\":1,\"version\":1"), "{}", "/mounted/policy.json")) {
      assertThrows(IllegalStateException.class, () -> ReviewedMaintenanceProfiles.read(
          invalid, protectedPolicy().toString(), groups, initial(), REALM, "fixture"));
    }
  }

  @Test
  void selectorMapsListsAndCompleteRoleSetsAreImmutable() {
    var found = assertDoesNotThrow(() -> read(ordinary(), protectedPolicy())).profiles();
    assertThrows(UnsupportedOperationException.class, () -> found.clear());
    assertThrows(UnsupportedOperationException.class, () -> found.get("USER").clear());
    assertThrows(UnsupportedOperationException.class, () -> found.get("USER").getFirst().realmRoles().clear());
  }
}
