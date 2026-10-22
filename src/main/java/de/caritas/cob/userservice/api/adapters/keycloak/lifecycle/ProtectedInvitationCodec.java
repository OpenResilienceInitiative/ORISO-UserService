package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.time.Clock;
import java.util.UUID;

/** Separate protected wire; the ordinary lifecycle proof domain is unchanged. */
public final class ProtectedInvitationCodec {
  public ProtectedInvitationCodec(byte[] key, Clock clock, String realmName, String realmId) {}

  public FrozenApproval freeze(
      UUID approvalId, String requestJson, String issuerRealm, String issuerSubject) {
    return null;
  }

  public VerifiedApproval verifyAuthorized(FrozenApproval frozen, String responseJson) {
    throw new IllegalArgumentException("Protected invitation approval is unavailable");
  }

  public String statusBody(FrozenApproval frozen) {
    return null;
  }

  public String statusProof(FrozenApproval frozen, TaskActorCredentials actor) {
    return null;
  }

  public record FrozenApproval(
      UUID approvalId,
      String requestJson,
      String issuerRealm,
      String issuerSubject,
      String requestDigest) {
    @Override
    public String toString() {
      return "FrozenApproval[scope=redacted]";
    }
  }

  public record VerifiedApproval(
      UUID approvalId, String scopeDigest, String requestDigest, String receiptJson) {
    @Override
    public String toString() {
      return "VerifiedApproval[scope=redacted]";
    }
  }
}
