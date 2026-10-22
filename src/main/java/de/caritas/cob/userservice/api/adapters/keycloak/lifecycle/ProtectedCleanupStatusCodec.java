package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.FrozenCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec.VerifiedRestriction;

/** Independent native compensation attestation; original restriction receipts remain immutable. */
public final class ProtectedCleanupStatusCodec {
  public ProtectedCleanupStatusCodec(AccountLifecycleCodec json,
      ProtectedInvitationRestrictionCodec restrictions, ProtectedAccountCodec accounts) {}

  public VerifiedFinalStatus verify(FrozenCancellation frozen, VerifiedRestriction original,
      VerifiedRecovery recovery, LocalOutcome local, byte[] response) {
    return null;
  }

  public static final class VerifiedFinalStatus {
    private final String responseJson;
    private VerifiedFinalStatus(String responseJson) { this.responseJson = responseJson; }
    public String responseJson() { return responseJson; }
    @Override public String toString() { return "ProtectedFinalCleanupStatus[redacted]"; }
  }
}
