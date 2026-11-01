package de.caritas.cob.userservice.api.service.identity.observation;

import java.time.Instant;
import java.util.List;

public record IdentityObservation(
    Instant observedAt,
    IdentityProbe probe,
    boolean complete,
    String occupancy,
    List<Layer> layers,
    List<Fact> facts,
    int status) {
  public enum Source {
    KEYCLOAK,
    ADMIN,
    CONSULTANT,
    ASKER
  }

  public enum Status {
    COMPLETE,
    LIMIT,
    DEPENDENCY_FAILURE
  }

  public record Fact(
      Source source,
      String identityId,
      String rowId,
      String username,
      String email,
      Long tenantId,
      String adminType,
      Boolean deleted,
      String deletedAt,
      Boolean enabled) {}

  public record Layer(Source source, Status status, List<Fact> facts) {
    public static Layer failed(Source source) {
      return new Layer(source, Status.DEPENDENCY_FAILURE, List.of());
    }
  }
}
