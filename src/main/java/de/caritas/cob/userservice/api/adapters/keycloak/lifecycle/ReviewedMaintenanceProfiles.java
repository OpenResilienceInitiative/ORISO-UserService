package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Data-only selectors from the same installation snapshots independently checked by Keycloak. */
public final class ReviewedMaintenanceProfiles {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> SQL_KINDS =
      Set.of("USER", "CONSULTANT", "AGENCY_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");

  public record Profile(String family, String id, long version, Set<String> realmRoles) {
    public Profile {
      realmRoles = Set.copyOf(realmRoles);
    }
  }

  private final Map<String, List<Profile>> profiles;

  private ReviewedMaintenanceProfiles(Map<String, List<Profile>> profiles) {
    this.profiles = profiles;
  }

  public static ReviewedMaintenanceProfiles read(
      String ordinaryJson,
      String protectedJson,
      Map<String, Set<String>> ordinaryGroups,
      Map<String, String> initialProtectedProfiles,
      String realmId,
      String realmName) {
    try {
      var ordinary = parse(ordinaryJson);
      var protectedPolicy = parse(protectedJson);
      if (number(ordinary, "version") != 1
          || !ordinary.path("native").isObject()
          || !text(protectedPolicy, "realmId", 255).equals(realmId)
          || !text(protectedPolicy, "realmName", 255).equals(realmName)
          || ordinaryGroups == null
          || ordinaryGroups.isEmpty()
          || initialProtectedProfiles == null
          || !initialProtectedProfiles.keySet().equals(Set.of("TENANT_ADMIN", "PLATFORM_ADMIN"))
          || new HashSet<>(initialProtectedProfiles.values()).size() != 2) throw invalid();
      var technical = strings(protectedPolicy.path("technicalRoles"), 1, 200, 255);
      var result = new TreeMap<String, List<Profile>>();
      SQL_KINDS.forEach(kind -> result.put(kind, new ArrayList<>()));
      var ids = new HashSet<String>();
      var groupIds = new HashSet<String>();
      var ordinaryPaths = new HashSet<String>();
      for (var profile : array(ordinary.path("ordinaryProfiles"), 20)) {
        fields(
            profile,
            Set.of(
                "id",
                "version",
                "groupId",
                "groupPath",
                "groupParentId",
                "realmRoles",
                "clientRoles",
                "allowUnassignedTenant",
                "selfOperations"));
        String id = text(profile, "id", 100), path = text(profile, "groupPath", 512);
        String group = text(profile, "groupId", 255), parent = text(profile, "groupParentId", 255);
        var roles = strings(profile.path("realmRoles"), 1, 10, 255);
        var kind = OrdinaryAccountCreation.kind(roles);
        if (kind == null
            || !ids.add(id)
            || !groupIds.add(group)
            || group.equals(parent)
            || !path.matches("/human-roles/[a-z0-9-]{1,80}")
            || !ordinaryPaths.add(path)
            || !roles.equals(ordinaryGroups.get(path))
            || !java.util.Collections.disjoint(roles, technical)
            || !profile.path("allowUnassignedTenant").isBoolean()) throw invalid();
        clientRoles(profile.path("clientRoles"));
        var operations = array(profile.path("selfOperations"), 10);
        var uniqueOperations = new HashSet<String>();
        for (var operation : operations) {
          if (!operation.isTextual()
              || !Set.of(
                      "account.read",
                      "account.locale",
                      "account.profile",
                      "account.email",
                      "account.password",
                      "otp.read",
                      "otp.app-setup",
                      "otp.delete",
                      "otp.email-start",
                      "otp.email-finish")
                  .contains(operation.textValue())
              || !uniqueOperations.add(operation.textValue())) throw invalid();
        }
        String sqlKind =
            "ASKER".equals(kind)
                ? "USER"
                : "CONSULTANT_AGENCY_ADMIN".equals(kind) ? "AGENCY_ADMIN" : kind;
        result.get(sqlKind).add(new Profile("ORDINARY", id, number(profile, "version"), roles));
      }
      if (!ordinaryPaths.equals(ordinaryGroups.keySet())) throw invalid();
      ids.clear();
      var protectedKinds = new TreeMap<String, String>();
      for (var profile : array(protectedPolicy.path("profiles"), 100)) {
        fields(
            profile,
            Set.of(
                "id",
                "version",
                "kind",
                "tenantModes",
                "groupId",
                "groupPath",
                "realmRoles",
                "clientRoles",
                "usernamePolicy",
                "firstNamePolicy",
                "lastNamePolicy"));
        String id = text(profile, "id", 255), kind = text(profile, "kind", 30);
        var roles = strings(profile.path("realmRoles"), 1, 100, 255);
        var modes = strings(profile.path("tenantModes"), 1, 2, 20);
        if (!ids.add(id)
            || !Set.of("TENANT_ADMIN", "PLATFORM_ADMIN").contains(kind)
            || !Set.of("EXISTING", "RESERVED_NEW").containsAll(modes)
            || "PLATFORM_ADMIN".equals(kind)
                && (!modes.equals(Set.of("EXISTING"))
                    || !roles.containsAll(Set.of("agency-admin", "tenant-admin")))
            || "TENANT_ADMIN".equals(kind)
                && !roles.contains("tenant-admin")
                && !roles.contains("single-tenant-admin")
            || !java.util.Collections.disjoint(roles, technical)
            || !"USERNAME_TRANSCODER_V1".equals(text(profile, "usernamePolicy", 80))
            || !"INVITE_WITH_EMAIL_LOCAL_PART_FALLBACK".equals(text(profile, "firstNamePolicy", 80))
            || !"INVITE_WITH_ADMIN_FALLBACK".equals(text(profile, "lastNamePolicy", 80)))
          throw invalid();
        text(profile, "groupId", 255);
        text(profile, "groupPath", 512);
        clientRoles(profile.path("clientRoles"));
        if (profile.path("clientRoles").has("realm-management")) throw invalid();
        protectedKinds.put(id, kind);
        result.get(kind).add(new Profile("PROTECTED", id, number(profile, "version"), roles));
      }
      initialProtectedProfiles.forEach(
          (kind, id) -> {
            if (!kind.equals(protectedKinds.get(id))) throw invalid();
          });
      result.replaceAll(
          (kind, profiles) -> {
            if (profiles.isEmpty() || profiles.size() > 20) throw invalid();
            return List.copyOf(profiles);
          });
      return new ReviewedMaintenanceProfiles(Map.copyOf(result));
    } catch (java.io.IOException | IllegalArgumentException | NullPointerException failure) {
      throw invalid();
    }
  }

  public Map<String, List<Profile>> profiles() {
    return profiles;
  }

  private static JsonNode parse(String raw) throws java.io.IOException {
    if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
    var value = JSON.readTree(raw);
    if (value == null || !value.isObject()) throw invalid();
    return value;
  }

  private static JsonNode array(JsonNode value, int maximum) {
    if (!value.isArray() || value.isEmpty() || value.size() > maximum) throw invalid();
    return value;
  }

  private static void fields(JsonNode value, Set<String> expected) {
    if (!value.isObject()) throw invalid();
    var actual = new HashSet<String>();
    value.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static String text(JsonNode owner, String name, int maximum) {
    var value = owner.path(name);
    if (!value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > maximum
        || value.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
    return value.textValue();
  }

  private static long number(JsonNode owner, String name) {
    var value = owner.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
      throw invalid();
    return value.longValue();
  }

  private static Set<String> strings(JsonNode values, int minimum, int maximum, int length) {
    if (!values.isArray() || values.size() < minimum || values.size() > maximum) throw invalid();
    var result = new HashSet<String>();
    String previous = null;
    for (var value : values) {
      if (!value.isTextual()
          || value.textValue().isBlank()
          || value.textValue().length() > length
          || value.textValue().chars().anyMatch(c -> c < 32 || c == 127)
          || previous != null && previous.compareTo(value.textValue()) >= 0) throw invalid();
      previous = value.textValue();
      result.add(previous);
    }
    return Set.copyOf(result);
  }

  private static void clientRoles(JsonNode value) {
    if (!value.isObject() || value.size() > 30) throw invalid();
    value
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getKey().isBlank()
                  || entry.getKey().length() > 255
                  || entry.getKey().chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
              strings(entry.getValue(), 1, 100, 255);
            });
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Reviewed maintenance profile catalog is unavailable");
  }
}
