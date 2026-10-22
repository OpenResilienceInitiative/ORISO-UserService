package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import org.springframework.web.client.RestTemplate;

/** Human approval is immediate; background recovery never stores or substitutes a human bearer. */
public final class KeycloakProtectedInvitations {
  public KeycloakProtectedInvitations(
      RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedInvitationCodec codec,
      String authServer,
      String realmName) {}

  public VerifiedApproval approve(FrozenApproval frozen, String currentHumanBearer) {
    return null;
  }

  public VerifiedApproval reconcile(FrozenApproval frozen) {
    return null;
  }

  public static final class RemoteFailure extends RuntimeException {
    private final int status;

    public RemoteFailure(int status) {
      super("Protected invitation command unavailable (status=" + status + ")");
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
