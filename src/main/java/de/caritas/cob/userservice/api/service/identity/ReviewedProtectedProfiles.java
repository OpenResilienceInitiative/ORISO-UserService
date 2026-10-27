package de.caritas.cob.userservice.api.service.identity;

import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.ProtectedProfileSelector;
import java.util.*;

/** Installed selector data only. Native provenance and both operation grants remain independent. */
final class ReviewedProtectedProfiles {
  record Transition(
      ProtectedProfileSelector selector, List<String> original, List<String> desired) {
    Transition {
      original = List.copyOf(original);
      desired = List.copyOf(desired);
    }
  }

  private final Map<String, List<ProtectedProfileSelector>> profiles;

  ReviewedProtectedProfiles(Map<String, List<ProtectedProfileSelector>> configured) {
    if (configured == null
        || !Set.of("TENANT_ADMIN", "PLATFORM_ADMIN").containsAll(configured.keySet()))
      throw new IllegalArgumentException("Unknown protected SQL kind");
    var copy = new TreeMap<String, List<ProtectedProfileSelector>>();
    configured.forEach(
        (kind, entries) -> {
          if (entries == null || entries.size() > 20)
            throw new IllegalArgumentException("Invalid protected profile catalog");
          var ids = new HashSet<String>();
          var roles = new HashSet<Set<String>>();
          var selectors = new ArrayList<ProtectedProfileSelector>();
          for (var entry : entries) {
            if (entry == null
                || !text(entry.id())
                || entry.version() <= 0
                || entry.completeRealmRoles() == null
                || entry.completeRealmRoles().isEmpty()
                || entry.completeRealmRoles().size() > 100
                || entry.completeRealmRoles().stream().anyMatch(role -> !text(role))
                || !ids.add(entry.id())
                || !roles.add(entry.completeRealmRoles()))
              throw new IllegalArgumentException("Invalid or ambiguous protected profile selector");
            selectors.add(
                new ProtectedProfileSelector(
                    entry.id(), entry.version(), Set.copyOf(entry.completeRealmRoles())));
          }
          copy.put(kind, List.copyOf(selectors));
        });
    profiles = Map.copyOf(copy);
  }

  private static boolean text(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= 255
        && value.chars().noneMatch(Character::isISOControl);
  }

  void requireConfigured(String kind) {
    if (profiles.getOrDefault(kind, List.of()).isEmpty())
      throw new ScopedIdentityCommandRequiredException();
  }

  Transition consultantAddition(String kind, List<String> original, Set<String> required) {
    requireConfigured(kind);
    requireRoles(required);
    if (original == null
        || original.isEmpty()
        || original.size() > 100
        || new HashSet<>(original).size() != original.size()
        || profiles.get(kind).stream()
                .filter(selector -> selector.completeRealmRoles().equals(Set.copyOf(original)))
                .count()
            != 1) throw new ScopedIdentityCommandRequiredException();
    var desired = new TreeSet<>(original);
    desired.addAll(required);
    var matches =
        profiles.get(kind).stream()
            .filter(selector -> selector.completeRealmRoles().equals(desired))
            .toList();
    if (matches.size() != 1) throw new ScopedIdentityCommandRequiredException();
    return new Transition(
        matches.getFirst(), original.stream().sorted().toList(), List.copyOf(desired));
  }

  Transition frozenConsultantAddition(String kind, String id, long version, Set<String> required) {
    requireConfigured(kind);
    requireRoles(required);
    var matches =
        profiles.get(kind).stream()
            .filter(selector -> selector.id().equals(id) && selector.version() == version)
            .toList();
    if (matches.size() != 1 || !matches.getFirst().completeRealmRoles().containsAll(required))
      throw new ScopedIdentityCommandRequiredException();
    // The original authenticated body/expectedState is immutable. Replay never selects a new body.
    var desired = matches.getFirst().completeRealmRoles().stream().sorted().toList();
    return new Transition(matches.getFirst(), desired, desired);
  }

  private static void requireRoles(Set<String> required) {
    if (required == null
        || !required.contains("consultant")
        || !Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
            .containsAll(required)) throw new ScopedIdentityCommandRequiredException();
  }
}
