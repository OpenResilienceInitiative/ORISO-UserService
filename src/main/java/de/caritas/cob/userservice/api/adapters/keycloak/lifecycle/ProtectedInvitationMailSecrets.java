package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

/** Private at-rest mail data; decrypting an intent never grants dispatch authority. */
final class ProtectedInvitationMailSecrets {
  ProtectedInvitationMailSecrets(byte[] contextKey, String realmId) {}

  String seal(Metadata metadata, String canonicalMail) {
    return null;
  }

  String open(Metadata independentlyCheckedMetadata, String envelope) {
    return null;
  }

  record Metadata(
      int v,
      String realmId,
      String approvalId,
      String workflowId,
      long inviteId,
      long authorizationRevision,
      String scopeDigest,
      String requestDigest,
      String tokenHash,
      String payloadDigest) {
    @Override
    public String toString() {
      return "ProtectedMailMetadata[redacted]";
    }
  }
}
