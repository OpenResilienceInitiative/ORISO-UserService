package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.model.IdentityCreationAttempt;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;

/** Typed local journal states; conversion preserves the existing VARCHAR database contract. */
enum CreationStatus {
  CREATION_REQUESTED,
  OPEN,
  LOCAL_RECONCILIATION_REQUIRED,
  RECOVERY_REQUESTED,
  LOCAL_CLEANUP_REQUESTED,
  COMMIT_REQUESTED,
  COMPENSATION_REQUESTED,
  COMMITTED,
  COMPENSATED;

  static CreationStatus fromCode(String code) {
    try {
      return valueOf(code);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new AccessDeniedException("Invalid owned creation journal state");
    }
  }

  boolean matches(IdentityCreationAttempt row) {
    return fromCode(row.getStatus()) == this;
  }

  static boolean in(IdentityCreationAttempt row, CreationStatus... states) {
    CreationStatus actual = fromCode(row.getStatus());
    return Arrays.asList(states).contains(actual);
  }

  static List<String> codes(CreationStatus... states) {
    return Arrays.stream(states).map(CreationStatus::name).toList();
  }

  void persist(IdentityCreationAttempt row) {
    row.setStatus(name());
  }

  boolean isFinalizationRequest() {
    return this == COMMIT_REQUESTED || this == COMPENSATION_REQUESTED;
  }

  CreationStatus terminal() {
    return switch (this) {
      case COMMIT_REQUESTED -> COMMITTED;
      case COMPENSATION_REQUESTED -> COMPENSATED;
      default -> throw new AccessDeniedException("Invalid creation finalization request");
    };
  }

  CreationStatus requiredRequest() {
    return switch (this) {
      case COMMITTED -> COMMIT_REQUESTED;
      case COMPENSATED -> COMPENSATION_REQUESTED;
      default -> throw new AccessDeniedException("Invalid creation terminal state");
    };
  }

  boolean acceptsRequestFrom(IdentityCreationAttempt row) {
    return isFinalizationRequest() && in(row, OPEN, this, terminal());
  }
}
