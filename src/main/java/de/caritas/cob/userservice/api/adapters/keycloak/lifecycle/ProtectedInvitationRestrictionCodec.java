package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCancellation.Reason;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.time.Clock;
import java.util.UUID;

/** Restriction acknowledgements are distinct from permission to issue or activate an account. */
public final class ProtectedInvitationRestrictionCodec {
  public ProtectedInvitationRestrictionCodec(
      ProtectedInvitationCodec approvals,
      AccountLifecycleCodec json,
      byte[] key,
      Clock clock,
      String realmId,
      String issuer) {}

  public FrozenCancellation freeze(
      FrozenApproval approval, String knownScopeDigest, UUID cancellationId, Reason reason) {
    return null;
  }

  public String cancelProof(FrozenCancellation cancellation, TaskActorCredentials actor) {
    return null;
  }

  public VerifiedRestriction verify(
      FrozenCancellation cancellation, byte[] response, boolean statusReconciliation) {
    return null;
  }

  public record FrozenCancellation(
      FrozenApproval approval,
      String knownScopeDigest,
      UUID cancellationId,
      Reason reason,
      String bodyJson) {
    @Override
    public String toString() {
      return "FrozenCancellation[scope=redacted]";
    }
  }

  public static final class VerifiedRestriction {
    private final String responseJson;
    private final boolean cleanupRequired;

    private VerifiedRestriction(String responseJson, boolean cleanupRequired) {
      this.responseJson = responseJson;
      this.cleanupRequired = cleanupRequired;
    }

    public String responseJson() {
      return responseJson;
    }

    public boolean cleanupRequired() {
      return cleanupRequired;
    }

    @Override
    public String toString() {
      return "VerifiedRestriction[scope=redacted]";
    }
  }
}
