package de.caritas.cob.userservice.api.adapters.keycloak;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.IdentityPasswordResetTargetLookup;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

/** Exact, bounded identity resolution; never select the first of ambiguous email owners. */
@Component
@RequiredArgsConstructor
public class KeycloakPasswordResetTargetLookup implements IdentityPasswordResetTargetLookup {
  private final KeycloakClient keycloakClient;

  @Override
  public Optional<Target> findPasswordResetTarget(String input) {
    if (input == null || input.isBlank()) {
      return Optional.empty();
    }
    String value = input.trim();
    var transcoder = new UsernameTranscoder();
    Set<String> aliases = new LinkedHashSet<>();
    aliases.add(value);
    aliases.add(transcoder.decodeUsername(value));
    aliases.add(transcoder.encodeUsername(value));
    var users = keycloakClient.getUsersResource();
    Set<String> ids = new LinkedHashSet<>();
    for (String alias : aliases) {
      var matches = exact(users, alias, null);
      // Exact queries cannot legitimately contain fuzzy matches. Fail closed on provider drift.
      if (matches.stream()
          .anyMatch(u -> !alias.equalsIgnoreCase(u.getUsername()) || blank(u.getId()))) {
        return Optional.empty();
      }
      matches.forEach(u -> ids.add(u.getId()));
      if (ids.size() > 1) {
        return Optional.empty();
      }
    }
    boolean matchedUsername = !ids.isEmpty();
    if (!matchedUsername) {
      var matches = exact(users, null, value);
      if (matches.size() != 1
          || !value.equalsIgnoreCase(matches.getFirst().getEmail())
          || blank(matches.getFirst().getId())) {
        return Optional.empty();
      }
      ids.add(matches.getFirst().getId());
    }
    String id = ids.iterator().next();
    // Full representation is required: brief search results need not contain attributes.
    var identity = users.get(id).toRepresentation();
    if (identity == null
        || !id.equals(identity.getId())
        || !Boolean.TRUE.equals(identity.isEnabled())
        || blank(identity.getEmail())
        || (matchedUsername
            ? aliases.stream().noneMatch(a -> a.equalsIgnoreCase(identity.getUsername()))
            : !value.equalsIgnoreCase(identity.getEmail()))) {
      return Optional.empty();
    }
    var values = identity.getAttributes() == null ? null : identity.getAttributes().get("tenantId");
    if (values == null || values.size() != 1 || values.getFirst() == null) {
      return Optional.empty();
    }
    try {
      long tenantId = Long.parseLong(values.getFirst());
      return tenantId > 0
          ? Optional.of(new Target(id, tenantId, identity.getEmail()))
          : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private List<UserRepresentation> exact(UsersResource users, String username, String email) {
    // Do not filter enabled here: an existing disabled username must block email fallback.
    return users.search(username, null, null, email, 0, 2, null, true, true);
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
