package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.VerifiedApproval;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Protected issuance has its own durable proposal; no bearer is stored. */
public final class JdbcProtectedInvitationJournal {
  public JdbcProtectedInvitationJournal(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ProtectedInvitationCodec codec,
      AccountLifecycleCodec json,
      Clock clock) {}

  public FrozenApproval propose(long inviteId, FrozenApproval proposal) {
    return null;
  }

  public FrozenApproval pending(long inviteId) {
    return null;
  }

  public void recordReady(UUID approvalId, VerifiedApproval approval) {}

  public <T> T withReadyInvite(long inviteId, Supplier<T> release) {
    return null;
  }

  public void cancel(long inviteId, Runnable invalidateLocalInvite) {}
}
