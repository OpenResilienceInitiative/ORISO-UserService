package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Focused outbound identity email-owner lookup contract. */
public interface IdentityEmailOwnerLookup {

  Optional<IdentityEmailOwner> findByEmail(String email);
}
