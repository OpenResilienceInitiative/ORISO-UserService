package de.caritas.cob.userservice.api.port.out;

import java.util.Optional;

/**
 * Focused outbound read of a user's account language (identity-provider {@code locale} attribute).
 * Used to send transactional notices in the language of the account (ORISO-UserService#1005).
 */
public interface IdentityLocaleLookup {

  Optional<String> findLocaleById(String userId);
}
