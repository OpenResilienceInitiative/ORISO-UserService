package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.VerifiedRestriction;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import org.springframework.web.client.RestTemplate;

/** Fixed reducing native routes, separate from human approval and account activation. */
public final class KeycloakProtectedRestrictions {
  public KeycloakProtectedRestrictions(RestTemplate http, TaskActorConfiguration actors,
      TaskActorTokenBroker broker, ProtectedInvitationCodec approvals,
      ProtectedInvitationRestrictionCodec restrictions, String authServer, String realmName) {}

  public VerifiedRestriction cancel(FrozenCancellation frozen) { return null; }
  public VerifiedRestriction reconcile(FrozenCancellation frozen) { return null; }
}
