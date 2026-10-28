package de.caritas.cob.userservice.api.service.identity.observation;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityOccupancyLookup;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityObservationService {
  private final AuthenticatedUser user;
  private final IdentityOccupancyLookup provider;
  private final IdentityOccupancyRawReader reader;

  public void requirePermission() {
    var roles = user.getRoles();
    var authorities = user.getGrantedAuthorities();
    if (!user.isPlatformAdmin()
        || roles == null
        || !roles.contains("user-admin")
        || roles.contains("technical")
        || roles.contains("notifications-technical")
        || authorities == null
        || !authorities.contains(USER_ADMIN)
        || authorities.contains(TECHNICAL_DEFAULT)
        || authorities.contains(NOTIFICATIONS_TECHNICAL))
      throw new AccessDeniedException("Forbidden");
  }

  public IdentityObservation observe(IdentityProbe probe) {
    requirePermission();
    var layers = new ArrayList<Layer>();
    layers.add(read(Source.KEYCLOAK, () -> provider.read(probe)));
    for (var source : List.of(Source.ADMIN, Source.CONSULTANT, Source.ASKER))
      layers.add(read(source, () -> reader.read(source, probe)));
    var identities = new LinkedHashSet<String>();
    var unique = new LinkedHashMap<String, Fact>();
    boolean limit = layers.stream().anyMatch(l -> l.status() == Status.LIMIT);
    for (var layer : layers)
      for (var fact : layer.facts()) {
        if (!identities.contains(fact.identityId()) && identities.size() == 20) {
          limit = true;
          continue;
        }
        identities.add(fact.identityId());
        unique.putIfAbsent(
            fact.source() + ":" + Objects.toString(fact.rowId(), fact.identityId()), fact);
      }
    boolean failure = layers.stream().anyMatch(l -> l.status() == Status.DEPENDENCY_FAILURE);
    boolean complete = !limit && !failure;
    boolean tupleConflict =
        identities.size() > 1
            || (probe.identityId() != null
                && identities.stream().anyMatch(id -> !probe.identityId().equals(id)));
    String occupancy =
        tupleConflict ? "CONFLICT" : !unique.isEmpty() ? "OCCUPIED" : complete ? "FREE" : "UNKNOWN";
    int status = failure ? 502 : limit || tupleConflict ? 409 : 200;
    return new IdentityObservation(
        Instant.now(),
        probe,
        complete,
        occupancy,
        List.copyOf(layers),
        List.copyOf(unique.values()),
        status);
  }

  private Layer read(Source source, Supplier<Layer> action) {
    try {
      return action.get();
    } catch (RuntimeException failure) {
      // Fail closed while retaining other observed layers; never expose dependency error details.
      log.warn("Identity occupancy source {} failed: {}", source, failure.getClass().getName());
      return Layer.failed(source);
    }
  }
}
