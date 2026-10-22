package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.identity.IdentitySession;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.ClaimedMagicLink;
import java.util.Optional;

/** Exchanges a trusted identity subject for an authenticated provider-neutral session. */
public interface IdentitySessionExchange {

  Optional<IdentitySession> exchangeForUser(String identityUserId);

  default Optional<IdentitySession> exchangeMagicLink(ClaimedMagicLink claim) {
    return Optional.empty();
  }
}
