package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** A recipient claim must be durable before creating a disabled protected account. */
public final class JdbcProtectedInvitationAcceptance {
  public JdbcProtectedInvitationAcceptance(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationCodec approvals,
      AccountLifecycleCodec json,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
  }

  public AcceptanceClaim claim(long inviteId, String tokenHash, JsonNode registration) {
    throw new IllegalStateException("Protected recipient acceptance is unavailable");
  }

  /** Only this SQL owner can mint the native creation handoff. */
  public static final class AcceptanceClaim {
    private final FrozenApproval frozen;
    private final VerifiedApproval approval;
    private final UUID claimId;
    private final UUID attemptId;
    private final UUID executionId;
    private final String registrationDigest;

    private AcceptanceClaim(
        FrozenApproval frozen,
        VerifiedApproval approval,
        UUID claimId,
        UUID attemptId,
        UUID executionId,
        String registrationDigest) {
      this.frozen = frozen;
      this.approval = approval;
      this.claimId = claimId;
      this.attemptId = attemptId;
      this.executionId = executionId;
      this.registrationDigest = registrationDigest;
    }

    public FrozenApproval frozen() { return frozen; }
    public VerifiedApproval approval() { return approval; }
    public UUID claimId() { return claimId; }
    public UUID attemptId() { return attemptId; }
    public UUID executionId() { return executionId; }
    public long journalVersion() { return 1; }
    public String registrationDigest() { return registrationDigest; }

    @Override
    public String toString() { return "ProtectedAcceptanceClaim[redacted]"; }
  }
}
