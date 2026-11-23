package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Read-only login status; an unknown identity is not a disabled account. */
public interface IdentityAccountStatusLookup {
  Optional<Boolean> findEnabledById(String userId);
}
