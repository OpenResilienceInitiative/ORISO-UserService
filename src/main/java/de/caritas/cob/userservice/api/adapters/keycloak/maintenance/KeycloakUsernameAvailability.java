package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestTemplate;

/**
 * Compiling diagnostic signature only, unregistered until actual public wire assertions reach RED.
 */
@RequiredArgsConstructor
public final class KeycloakUsernameAvailability implements ScopedUsernameAvailability {
  private final @NonNull RestTemplate http;
  private final @NonNull TaskActorConfiguration actors;
  private final @NonNull TaskActorTokenBroker broker;
  private final @NonNull AccountLifecycleCodec codec;
  private final @NonNull String authServer;
  private final @NonNull String realmName;
  private final @NonNull String realmId;

  @Override
  public boolean available(String exactUsername) {
    throw new ScopedAvailabilityUnavailableException();
  }
}
