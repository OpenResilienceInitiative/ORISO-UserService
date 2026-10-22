package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.*;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Durable original intent before any native account RPC. Inert until hosted assertion RED. */
public final class JdbcProtectedAccountCreation {
  public JdbcProtectedAccountCreation(JdbcTemplate jdbc, PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal, ProtectedAccountCodec accounts,
      AccountLifecycleCodec json, Clock clock) {}
  public void prepare(AcceptanceClaim claim, PreparedCreation prepared) { throw unavailable(); }
  public PreparedMetadata metadata(AcceptanceClaim claim) { throw unavailable(); }
  public void recordCreated(AcceptanceClaim claim, VerifiedCreated created) { throw unavailable(); }
  public VerifiedCreated created(AcceptanceClaim claim) { throw unavailable(); }
  public PreparedRecovery recovery(AcceptanceClaim claim, long expectedGeneration) { throw unavailable(); }
  private static IllegalStateException unavailable() { return new IllegalStateException("Protected account creation pending"); }
}
