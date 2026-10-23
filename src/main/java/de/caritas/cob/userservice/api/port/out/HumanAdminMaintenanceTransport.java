package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance.CheckedAccount;
import java.util.List;
import java.util.UUID;

/** Fixed native operations consume only the private current application authorization. */
public interface HumanAdminMaintenanceTransport {
  record Projection(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      boolean enabled,
      boolean emailVerified,
      Long tenantId,
      String preferredLanguage,
      List<String> effectiveRoles,
      boolean passwordChangeRequired,
      String state) {
    public Projection {
      effectiveRoles = List.copyOf(effectiveRoles);
    }
  }

  record FrozenProfile(String body, String bindingDigest) {
    @Override
    public String toString() {
      return "FrozenProfile[payload=redacted]";
    }
  }

  Projection read(CheckedAccount account);

  record FactorStatus(boolean configured, boolean active) {}

  record FactorProjection(Projection account, FactorStatus twoFactor) {}

  default FactorProjection factorStatus(CheckedAccount account) {
    throw new ScopedIdentityCommandRequiredException();
  }

  String profileRequestDigest(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation);

  FrozenProfile freezeProfile(
      CheckedAccount account, IdentityProfileUpdate profile, UUID operation, String expectedState);

  sealed interface ProfileOutcome permits ProfileApplied, ProfileNoEffect {}

  record ProfileApplied(String receipt) implements ProfileOutcome {
    @Override
    public String toString() {
      return "ProfileApplied[receipt=redacted]";
    }
  }

  record ProfileNoEffect(int status, String receipt) implements ProfileOutcome {
    @Override
    public String toString() {
      return "ProfileNoEffect[receipt=redacted]";
    }
  }

  default ProfileOutcome executeProfile(CheckedAccount account, FrozenProfile frozen) {
    return new ProfileApplied(updateProfile(account, frozen));
  }

  default ProfileNoEffect verifyProfileNoEffect(
      CheckedAccount account, FrozenProfile frozen, String receipt) {
    throw new ScopedIdentityCommandRequiredException();
  }

  default void verifyStoredProfileNoEffect(
      CheckedAccount current,
      UUID originalOperation,
      String requestDigest,
      FrozenProfile original,
      String receipt) {
    throw new ScopedIdentityCommandRequiredException();
  }

  String updateProfile(CheckedAccount account, FrozenProfile frozen);

  String verifyProfileReceipt(CheckedAccount account, FrozenProfile frozen, String receipt);
}
