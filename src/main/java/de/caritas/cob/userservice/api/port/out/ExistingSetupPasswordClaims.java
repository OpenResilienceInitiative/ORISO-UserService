package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import java.time.LocalDateTime;
import java.util.UUID;

/** Private workflow journal data. A row or binding never authorizes an identity-provider call. */
public interface ExistingSetupPasswordClaims {

  record Binding(
      long inviteId,
      String accountId,
      String tokenHash,
      LocalDateTime expiresAt,
      AccountInviteTargetRole role,
      Long tenantId,
      String username,
      String email) {
    @Override
    public String toString() {
      return "SetupPasswordBinding[redacted]";
    }
  }

  record Claim(UUID id, long version, Binding binding, String requestDigest) {
    @Override
    public String toString() {
      return "SetupPasswordClaim[redacted]";
    }
  }

  /**
   * Joins the caller's exact owning SQL transaction. An exact acknowledged outcome is returned as
   * historical data only; it cannot pass requireCurrentWithinOwner or authorize another effect.
   */
  Claim claimWithinOwner(Binding binding, String completeKeyedRequestDigest);

  /** Existing exact custody only, bounded to original expiry plus seven days; never allocates. */
  default java.util.Optional<Claim> recoverWithinOwner(Binding binding, String requestDigest) {
    throw new IllegalStateException("Existing setup recovery unavailable");
  }

  /** Recovery data only; expiry still forbids another native mutation. */
  default void requireRecoveryWithinOwner(Claim claim) {
    throw new IllegalStateException("Existing setup recovery unavailable");
  }

  void requireCurrentWithinOwner(Claim claim);

  void markUnknownWithinOwner(Claim claim);

  void rejectWithinOwner(Claim claim);

  /** A native signed policy terminal; its exact same request remains a historical refusal. */
  default void rejectPolicyWithinOwner(Claim claim) {
    throw new IllegalStateException("Existing setup policy custody unavailable");
  }

  /** Verified non-policy terminal. No release or version advance is allowed. */
  default void refuseWithinOwner(Claim claim) {
    throw new IllegalStateException("Existing setup refusal custody unavailable");
  }

  void acknowledgeWithinOwner(Claim claim);
}
