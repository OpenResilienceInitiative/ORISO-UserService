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

  void requireCurrentWithinOwner(Claim claim);

  void markUnknownWithinOwner(Claim claim);

  void rejectWithinOwner(Claim claim);

  void acknowledgeWithinOwner(Claim claim);
}
