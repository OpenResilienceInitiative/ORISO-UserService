package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleDtos.LocalOutcome;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCommitted;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec.VerifiedCreated;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.Completion;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap.PreparedBootstrap;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.Policy;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Owns local protected-account effects; native activation requires its committed private handle. */
public final class JdbcProtectedAccountActivation {
  public JdbcProtectedAccountActivation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedAccountCodec accounts,
      AccountLifecycleCodec json,
      ProtectedOtpBootstrap otp,
      AdminRepository admins,
      AccountInactivityEnrollmentService inactivity,
      Clock clock) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
    journal.requireOwner(jdbc, manager);
  }

  public IdentityOtpCredential stage(
      AcceptanceClaim claim, PendingOwnedAccount pending, PreparedBootstrap prepared, Policy policy) {
    throw unavailable();
  }

  public IdentityOtpCredential currentSetup(AcceptanceClaim claim) {
    throw unavailable();
  }

  public VerifiedCreated created(AcceptanceClaim claim) {
    throw unavailable();
  }

  public ReadyActivation ready(AcceptanceClaim claim, Completion completion) {
    throw unavailable();
  }

  public void acknowledge(ReadyActivation ready, VerifiedCommitted committed) {
    throw unavailable();
  }

  public static final class ReadyActivation {
    private ReadyActivation() {}

    public VerifiedCreated created() {
      throw unavailable();
    }

    public LocalOutcome outcome() {
      throw unavailable();
    }

    @Override
    public String toString() {
      return "ProtectedReadyActivation[redacted]";
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Protected local activation is not available");
  }
}
