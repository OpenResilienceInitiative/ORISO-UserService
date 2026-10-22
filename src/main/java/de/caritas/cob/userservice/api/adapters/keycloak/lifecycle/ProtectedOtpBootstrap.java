package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts.PendingOwnedAccount;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/** Callable signature contract; implementation is gated on the actual original OTP route proof. */
public final class ProtectedOtpBootstrap {
  public ProtectedOtpBootstrap(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      AccountLifecycleCodec codec,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      RestTemplate http,
      HttpServletRequest request,
      Clock clock,
      String authServer,
      String realmName,
      String realmId) {
    ProtectedInvitationSqlOwner.require(jdbc, manager);
  }

  /** Outside SQL: actual pending owned native handle, then finite OTP prepare RPC. */
  public PreparedBootstrap prepare(AcceptanceClaim claim, PendingOwnedAccount pending) {
    throw unavailable();
  }

  /** Required owning SQL: encrypted preparation and WAITING_OTP are committed together. No RPC. */
  public IdentityOtpCredential stagePrepared(
      AcceptanceClaim currentClaim, PreparedBootstrap prepared) {
    throw unavailable();
  }

  /** Current same-claim private journal read, for a known committed register retry. No RPC. */
  public IdentityOtpCredential currentSetup(AcceptanceClaim currentClaim) {
    throw unavailable();
  }

  /** All three methods capture actual servlet key/method/path/body inside this adapter. */
  public BootstrapAttempt setupApp(AcceptanceClaim currentClaim, String initialCode) {
    throw unavailable();
  }

  public BootstrapAttempt startEmail(AcceptanceClaim currentClaim) {
    throw unavailable();
  }

  public BootstrapAttempt finishEmail(AcceptanceClaim currentClaim, String initialCode) {
    throw unavailable();
  }

  /** Reconstruct opaque success only from the independently checked committed private journal. */
  public Completion completed(AcceptanceClaim currentClaim) {
    throw unavailable();
  }

  public enum AttemptState {
    COMPLETE,
    INVALID,
    PENDING,
    UNKNOWN
  }

  public static final class PreparedBootstrap {
    private PreparedBootstrap() {}

    @Override
    public String toString() {
      return "PreparedProtectedOtp[redacted]";
    }
  }

  public static final class BootstrapAttempt {
    private BootstrapAttempt() {}

    public AttemptState state() {
      throw unavailable();
    }

    /** Available only for COMPLETE; private constructor of Completion supplies no raw-tuple API. */
    public Completion completion() {
      throw unavailable();
    }

    @Override
    public String toString() {
      return "ProtectedOtpAttempt[redacted]";
    }
  }

  public static final class Completion {
    private Completion() {}

    public UUID claimId() {
      throw unavailable();
    }

    public UUID attemptId() {
      throw unavailable();
    }

    public UUID executionId() {
      throw unavailable();
    }

    public UUID bootstrapIncarnation() {
      throw unavailable();
    }

    public String accountId() {
      throw unavailable();
    }

    /** Metadata-only MAC-bound native completion receipt; never setup material/code/JWT/address. */
    public String receipt() {
      throw unavailable();
    }

    @Override
    public String toString() {
      return "ProtectedOtpCompletion[redacted]";
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Protected OTP bootstrap pending implementation");
  }
}
