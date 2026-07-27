package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/** Exchanges a trusted identity subject for an authenticated provider-neutral session. */
public interface IdentitySessionExchange {

  Optional<IdentitySession> exchangeForUser(String identityUserId);
}
