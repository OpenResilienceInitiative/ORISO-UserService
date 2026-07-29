package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Focused outbound lookup for the identity owning an email address. */
public interface IdentityEmailOwnerLookup {

  Optional<IdentityEmailOwner> findByEmail(String email);
}
