package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Focused outbound identity profile read contract. */
public interface IdentityProfileLookup {

  Optional<IdentityProfile> findById(String userId);
}
