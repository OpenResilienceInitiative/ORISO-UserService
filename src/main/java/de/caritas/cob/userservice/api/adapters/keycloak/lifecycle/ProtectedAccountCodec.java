package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.AccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import java.time.Clock;
import java.util.UUID;

/** Finite protected wire only. Deliberate TDD stub; no SQL, HTTP or activation authority. */
public final class ProtectedAccountCodec {
  public ProtectedAccountCodec(
      byte[] key,
      Clock clock,
      ProtectedInvitationCodec approvals,
      String realmName,
      String realmId,
      String realmIssuer) {}

  String realmName() {
    return null;
  }

  public PreparedCreation prepare(
      AcceptanceClaim claim, JsonNode registration, AccountCreation account) {
    return null;
  }

  public byte[] createBody(PreparedCreation prepared) {
    return null;
  }

  public String createProof(PreparedCreation prepared, TaskActorCredentials actor) {
    return null;
  }

  public VerifiedCreated verifyCreated(PreparedCreation prepared, byte[] response) {
    return null;
  }

  public byte[] ownedReadBody(VerifiedCreated created) {
    return null;
  }

  public String ownedReadProof(VerifiedCreated created, TaskActorCredentials actor) {
    return null;
  }

  /** Pure comparison only; the actual HTTP bridge owns the pending-owned account handle. */
  public void checkOwnedProjection(VerifiedCreated created, byte[] projection) {}

  public byte[] commitBody(VerifiedCreated created, LocalOutcome local) {
    return null;
  }

  public String commitProof(
      VerifiedCreated created, LocalOutcome local, TaskActorCredentials actor) {
    return null;
  }

  public VerifiedCommitted verifyCommitted(
      VerifiedCreated created, LocalOutcome local, byte[] response) {
    return null;
  }

  public static final class PreparedCreation {
    private PreparedCreation() {}

    public UUID approvalId() {
      return null;
    }

    public UUID claimId() {
      return null;
    }

    public UUID attemptId() {
      return null;
    }

    public UUID executionId() {
      return null;
    }

    public String creationFingerprint() {
      return null;
    }

    @Override
    public String toString() {
      return "ProtectedPreparedCreation[redacted]";
    }
  }

  /** Authenticated creation ownership only; never an OTP/local-account authority handle. */
  public static final class VerifiedCreated {
    private VerifiedCreated() {}

    public String realmId() {
      return null;
    }

    public UUID approvalId() {
      return null;
    }

    public UUID workflowId() {
      return null;
    }

    public String inviteId() {
      return null;
    }

    public long authorizationRevision() {
      return 0;
    }

    public long tenantId() {
      return 0;
    }

    public String registrationKind() {
      return null;
    }

    public String profileId() {
      return null;
    }

    public String initialGroupId() {
      return null;
    }

    public String initialGroupPath() {
      return null;
    }

    public String initialRoleFingerprint() {
      return null;
    }

    public UUID claimId() {
      return null;
    }

    public UUID attemptId() {
      return null;
    }

    public UUID executionId() {
      return null;
    }

    public String accountId() {
      return null;
    }

    public String creationFingerprint() {
      return null;
    }

    public String creationProof() {
      return null;
    }

    @Override
    public String toString() {
      return "ProtectedVerifiedCreated[redacted]";
    }
  }

  public static final class VerifiedCommitted {
    private VerifiedCommitted() {}

    public UUID claimId() {
      return null;
    }

    public UUID attemptId() {
      return null;
    }

    public String accountId() {
      return null;
    }

    public String terminalReceipt() {
      return null;
    }

    @Override
    public String toString() {
      return "ProtectedVerifiedCommitted[redacted]";
    }
  }
}
