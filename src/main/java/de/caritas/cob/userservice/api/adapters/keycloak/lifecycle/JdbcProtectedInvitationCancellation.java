package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec.FrozenApproval;
import java.time.Clock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Durable reducing intent precedes native RPC; reservation reuse requires the saved exact ACK. */
public final class JdbcProtectedInvitationCancellation implements ProtectedInvitationCancellation {
  public JdbcProtectedInvitationCancellation(JdbcTemplate jdbc, PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal, ProtectedInvitationRestrictionCodec restrictions,
      AccountLifecycleCodec json, KeycloakProtectedRestrictions receiver,
      Function<Long, FrozenApproval> missingProposal, Clock clock) {}

  @Override public void cancel(long inviteId, Reason reason, Runnable invalidateLocalInvite) {}
  @Override public <T> T withAcknowledged(long inviteId, Supplier<T> releaseOrTransfer) {return null;}
}
