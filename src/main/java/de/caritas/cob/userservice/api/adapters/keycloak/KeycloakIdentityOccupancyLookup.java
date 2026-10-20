package de.caritas.cob.userservice.api.adapters.keycloak;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;

import de.caritas.cob.userservice.api.port.out.IdentityOccupancyLookup;
import de.caritas.cob.userservice.api.service.identity.observation.IdentityProbe;
import de.caritas.cob.userservice.api.service.identity.observation.IdentityUsername;
import jakarta.ws.rs.NotFoundException;
import java.util.*;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

/** Bounded exact reads against the configured realm only, including disabled identities. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakIdentityOccupancyLookup implements IdentityOccupancyLookup {
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 20;
  private static final int MAX_CANDIDATES = PAGE_SIZE * MAX_PAGES;
  private final KeycloakClient client;

  @Override
  public Layer read(IdentityProbe probe) {
    var facts = new LinkedHashMap<String, Fact>();
    Status status = Status.COMPLETE;
    try {
      var users = client.getUsersResource();
      if (probe.identityId() != null) {
        try {
          add(probe, users.get(probe.identityId()).toRepresentation(), facts);
        } catch (NotFoundException absent) {
          /* Only an exact ID 404 establishes absence. */
        }
      }
      for (String username : probe.usernames()) {
        if (!pages(
            probe,
            first -> users.search(username, null, null, null, first, PAGE_SIZE, null, null, true),
            facts)) status = Status.LIMIT;
        if (facts.size() > 20) break;
      }
      if (!probe.usernames().isEmpty()
          && facts.size() <= 20
          && !candidates(
              probe,
              users.search("enc.", null, null, null, 0, MAX_CANDIDATES, null, null, false),
              facts)) status = Status.LIMIT;
      if (probe.email() != null
          && facts.size() <= 20
          && !pages(
              probe,
              first ->
                  users.search(null, null, null, probe.email(), first, PAGE_SIZE, null, null, true),
              facts)) status = Status.LIMIT;
    } catch (RuntimeException dependencyFailure) {
      // Keep positive facts, but a failed provider observation cannot establish absence.
      log.warn(
          "Identity occupancy source {} failed: {}",
          Source.KEYCLOAK,
          dependencyFailure.getClass().getName());
      status = Status.DEPENDENCY_FAILURE;
    }
    if (facts.size() > 20 && status != Status.DEPENDENCY_FAILURE) status = Status.LIMIT;
    var bounded = facts.values().stream().limit(20).toList();
    return new Layer(Source.KEYCLOAK, status, bounded);
  }

  private boolean candidates(
      IdentityProbe probe, List<UserRepresentation> rows, Map<String, Fact> facts) {
    if (rows == null || rows.size() > MAX_CANDIDATES)
      throw new IllegalStateException("Invalid provider candidate response");
    // One bounded response avoids skipping persistent matches between offset requests.
    boolean resolved = collect(probe, rows, facts);
    return resolved && rows.size() < MAX_CANDIDATES;
  }

  private boolean pages(
      IdentityProbe probe, IntFunction<List<UserRepresentation>> query, Map<String, Fact> facts) {
    boolean resolved = true;
    for (int page = 0; page < MAX_PAGES; page++) {
      var rows = query.apply(page * PAGE_SIZE);
      if (rows == null || rows.size() > PAGE_SIZE)
        throw new IllegalStateException("Invalid provider page");
      resolved &= collect(probe, rows, facts);
      if (facts.size() > 20) return false;
      if (rows.size() < PAGE_SIZE) return resolved;
    }
    return false;
  }

  private boolean collect(
      IdentityProbe probe, List<UserRepresentation> rows, Map<String, Fact> facts) {
    boolean resolved = true;
    for (var user : rows) {
      if (!probe.usernames().isEmpty() && !IdentityUsername.decode(user.getUsername()).resolved())
        resolved = false;
      add(probe, user, facts);
      if (facts.size() > 20) return false;
    }
    return resolved;
  }

  private void add(IdentityProbe probe, UserRepresentation user, Map<String, Fact> facts) {
    if (user == null || user.getId() == null || user.getId().isBlank())
      throw new IllegalStateException("Invalid provider identity");
    if (probe.matches(user.getId(), user.getUsername(), user.getEmail()))
      facts.putIfAbsent(
          user.getId(),
          new Fact(
              Source.KEYCLOAK,
              user.getId(),
              null,
              user.getUsername(),
              user.getEmail(),
              null,
              null,
              null,
              null,
              user.isEnabled()));
  }
}
