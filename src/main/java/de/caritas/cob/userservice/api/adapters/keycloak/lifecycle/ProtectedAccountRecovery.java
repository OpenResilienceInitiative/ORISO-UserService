package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.PreparedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCleanup;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import org.springframework.web.client.RestTemplate;

/** Fixed native cleanup RPCs; never a generic account deletion port. */
public final class ProtectedAccountRecovery {
  public ProtectedAccountRecovery(RestTemplate http, TaskActorConfiguration actors,
      TaskActorTokenBroker broker, ProtectedAccountCodec codec, String authServer, String realmName) {}
  public VerifiedRecovery recover(PreparedRecovery recovery) { throw new IllegalStateException("Protected recovery unavailable"); }
  public VerifiedCleanup compensate(VerifiedRecovery recovery, LocalOutcome local) { throw new IllegalStateException("Protected recovery unavailable"); }
}
