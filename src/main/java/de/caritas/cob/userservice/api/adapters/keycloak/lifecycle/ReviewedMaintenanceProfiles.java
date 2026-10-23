package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Data-only selectors from the same installation snapshots independently checked by Keycloak. */
public final class ReviewedMaintenanceProfiles {
  public record Profile(String family, String id, long version, Set<String> realmRoles) {}

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
    throw new IllegalStateException("Reviewed maintenance profile catalog is unavailable");
  }

  public Map<String, List<Profile>> profiles() {
    return profiles;
  }
}
