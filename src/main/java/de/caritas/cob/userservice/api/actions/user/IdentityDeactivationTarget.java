package de.caritas.cob.userservice.api.actions.user;

import de.caritas.cob.userservice.api.adapters.keycloak.commands.IdentityCommandAuthorization;
import de.caritas.cob.userservice.api.model.User;
import java.util.Objects;

/** Carries the already checked lifecycle authority through the action registry. */
public record IdentityDeactivationTarget(User user, IdentityCommandAuthorization origin) {
  public IdentityDeactivationTarget {
    Objects.requireNonNull(user);
    Objects.requireNonNull(origin);
  }
}
