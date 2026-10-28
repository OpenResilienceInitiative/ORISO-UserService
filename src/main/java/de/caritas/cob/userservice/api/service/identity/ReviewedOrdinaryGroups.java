package de.caritas.cob.userservice.api.service.identity;

import de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException;
import java.util.*;

/**
 * Immutable installed group data. Native current/desired profiles independently decide authority.
 */
final class ReviewedOrdinaryGroups {
  record Transition(String group, List<String> original, List<String> desired) {
    Transition {
      original = List.copyOf(original);
      desired = List.copyOf(desired);
    }
  }

  private final Map<String, Set<String>> groups;

  ReviewedOrdinaryGroups(Map<String, Set<String>> configured) {
    Objects.requireNonNull(configured);
    var copy = new TreeMap<String, Set<String>>();
    for (var entry : configured.entrySet()) {
      if (!entry.getKey().matches("/human-roles/[a-z0-9-]{1,80}")
          || entry.getValue() == null
          || entry.getValue().isEmpty()
          || entry.getValue().size() > 32
          || entry.getValue().stream()
              .anyMatch(role -> role == null || role.isBlank() || role.length() > 255))
        throw new IllegalArgumentException("Invalid reviewed ordinary group map");
      copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
    }
    if (new HashSet<>(copy.values()).size() != copy.size())
      throw new IllegalArgumentException("Reviewed groups must be unambiguous");
    groups = Map.copyOf(copy);
  }

  void requireConfigured() {
    if (groups.isEmpty()) throw new ScopedIdentityCommandRequiredException();
  }

  Transition groupChat(boolean enabled, List<String> original) {
    requireConfigured();
    if (original == null
        || original.isEmpty()
        || original.size() > 32
        || !original.contains("consultant")
        || new HashSet<>(original).size() != original.size())
      throw new ScopedIdentityCommandRequiredException();
    var desired = new TreeSet<>(original);
    if (enabled) desired.add("group-chat-consultant");
    else desired.remove("group-chat-consultant");
    var match =
        groups.entrySet().stream().filter(entry -> entry.getValue().equals(desired)).toList();
    if (match.size() != 1) throw new ScopedIdentityCommandRequiredException();
    return new Transition(
        match.getFirst().getKey(), original.stream().sorted().toList(), List.copyOf(desired));
  }

  Transition agencyAdminAddition(List<String> original) {
    requireConfigured();
    if (original == null
        || original.isEmpty()
        || new HashSet<>(original).size() != original.size()
        || !Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
            .containsAll(original)
        || !original.contains("consultant")
        || original.contains("restricted-agency-admin") != original.contains("user-admin"))
      throw new ScopedIdentityCommandRequiredException();
    var desired = new TreeSet<>(original);
    desired.addAll(Set.of("restricted-agency-admin", "user-admin"));
    var matches =
        groups.entrySet().stream().filter(entry -> entry.getValue().equals(desired)).toList();
    if (matches.size() != 1) throw new ScopedIdentityCommandRequiredException();
    return new Transition(
        matches.getFirst().getKey(), original.stream().sorted().toList(), List.copyOf(desired));
  }

  Transition frozenAgencyAdminAddition(String group) {
    requireConfigured();
    var desired = groups.get(group);
    if (desired == null) throw new ScopedIdentityCommandRequiredException();
    var result = agencyAdminAddition(desired.stream().sorted().toList());
    if (!result.group().equals(group)) throw new ScopedIdentityCommandRequiredException();
    return result;
  }

  Transition consultantAddition(List<String> original, Set<String> required) {
    requireConfigured();
    if (original == null
        || new HashSet<>(original).size() != original.size()
        || !Set.copyOf(original).equals(Set.of("restricted-agency-admin", "user-admin"))
        || !groups.containsValue(Set.copyOf(original))
        || required == null
        || !required.contains("consultant")
        || !Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
            .containsAll(required)) throw new ScopedIdentityCommandRequiredException();
    var desired = new TreeSet<>(original);
    desired.addAll(required);
    var matches =
        groups.entrySet().stream().filter(entry -> entry.getValue().equals(desired)).toList();
    if (matches.size() != 1) throw new ScopedIdentityCommandRequiredException();
    return new Transition(
        matches.getFirst().getKey(), original.stream().sorted().toList(), List.copyOf(desired));
  }

  Transition consultantAssignment(List<String> original, Set<String> required) {
    requireConfigured();
    if (original == null
        || new HashSet<>(original).size() != original.size()
        || !original.containsAll(Set.of("consultant", "restricted-agency-admin", "user-admin"))
        || !Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
            .containsAll(original)
        || !groups.containsValue(Set.copyOf(original))
        || required == null
        || !required.contains("consultant")
        || !Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
            .containsAll(required)) throw new ScopedIdentityCommandRequiredException();
    var desired = new TreeSet<>(original);
    desired.addAll(required);
    var matches =
        groups.entrySet().stream().filter(entry -> entry.getValue().equals(desired)).toList();
    if (matches.size() != 1) throw new ScopedIdentityCommandRequiredException();
    return new Transition(
        matches.getFirst().getKey(), original.stream().sorted().toList(), List.copyOf(desired));
  }

  Transition frozenConsultantAssignment(String group, Set<String> required) {
    var selected = groups.get(group);
    if (selected == null || !selected.containsAll(required))
      throw new ScopedIdentityCommandRequiredException();
    return consultantAssignment(selected.stream().sorted().toList(), required);
  }

  Transition frozenConsultantAddition(String group, Set<String> required) {
    requireConfigured();
    var selected = groups.get(group);
    var expected = consultantAddition(List.of("restricted-agency-admin", "user-admin"), required);
    if (selected == null || !expected.group().equals(group))
      throw new ScopedIdentityCommandRequiredException();
    return new Transition(
        group, selected.stream().sorted().toList(), selected.stream().sorted().toList());
  }
}
